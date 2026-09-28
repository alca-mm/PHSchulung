# =====================================================================================
# phishing-awareness-trainer — Produktions-Container (Multi-Stage-Build)
#
# Internes, autorisiertes Security-Awareness-Trainingssystem (siehe CLAUDE.md).
# Dieses Image enthaelt AUSSCHLIESSLICH die kompilierte Backend-Anwendung als
# ausfuehrbares Spring-Boot-Jar. Es enthaelt KEINE Secrets: saemtliche Konfiguration
# (SMTP-Zugangsdaten, Admin-Login APP_ADMIN_USERNAME/APP_ADMIN_PASSWORD, PORT,
# APP_DATA_DIR usw.) wird zur Laufzeit ueber Environment-Variablen bereitgestellt.
#
# Persistenz: SQLite-Datenbank und generierte, passive Trainingsdateien liegen unter
# /data und MUESSEN auf einem gemounteten Volume liegen (siehe VOLUME weiter unten),
# damit sie einen Container-Neustart ueberdauern.
#
# Der statische Frontend-Teil (index.html, admin/, assets/, styles.css) wird separat
# ueber GitHub Pages ausgeliefert und ist bewusst NICHT Teil dieses Images.
# =====================================================================================


# -------------------------------------------------------------------------------------
# Stage 1: Build — kompiliert und paketiert die Anwendung mit dem Maven-Wrapper.
# Basis: vollstaendiges JDK 21 (Eclipse Temurin), da zum Kompilieren ein JDK noetig ist.
# -------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# 1) Zuerst NUR die fuer die Abhaengigkeitsaufloesung noetigen Dateien kopieren
#    (Maven-Wrapper + pom.xml). Der Maven-Wrapper ist vom Typ "only-script"
#    (kein maven-wrapper.jar noetig); das mvnw-Skript laedt Maven selbst herunter.
#    Diese Reihenfolge maximiert das Docker-Layer-Caching: solange sich die pom.xml
#    nicht aendert, bleibt der Dependency-Cache-Layer gueltig.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# 2) Wrapper ausfuehrbar machen (Ausfuehrungsbit kann unter Windows verloren gehen)
#    und den Abhaengigkeits-Cache vorwaermen. Fehlt eine Abhaengigkeit hier, wird sie
#    spaeter beim package-Schritt nachgeladen — der Build bleibt also robust.
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# 3) Erst jetzt den Quellcode kopieren und paketieren.
#    WICHTIG: Tests werden NICHT im Image gebaut (-DskipTests). Die Tests laufen
#    autoritativ vorab in der CI (siehe CLAUDE.md, Standardbefehl ./mvnw -B test).
COPY src/ src/
RUN ./mvnw -B -DskipTests package


# -------------------------------------------------------------------------------------
# Stage 2: Runtime — schlankes JRE-21-Image, nur zum Ausfuehren des fertigen Jars.
# Basis: Eclipse Temurin JRE 21 auf glibc/Ubuntu (bewusst KEIN Alpine/musl, damit die
# nativen Bibliotheken von sqlite-jdbc und die Schriftverarbeitung von PDFBox/POI
# zuverlaessig funktionieren).
# -------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

# Nicht-root-Benutzer und -Gruppe anlegen (feste UID/GID 10001). Der Prozess laeuft
# nach dem Prinzip der minimalen Rechte NICHT als root. Kein Login-Shell-Zugang.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home --shell /usr/sbin/nologin app \
 && mkdir -p /app /data \
 && chown -R 10001:10001 /app /data

# Persistentes Datenverzeichnis fuer SQLite (/data/app.db) und generierte Dateien.
# Als benannter/anonymer Volume-Mount deklariert; die Plattform muss hier ein
# dauerhaftes Volume mounten. Ownership wurde zuvor auf UID 10001 gesetzt.
VOLUME ["/data"]

# Nur das fertige, von spring-boot-maven-plugin repackte Jar aus der Build-Stage
# uebernehmen. Der Glob target/*.jar trifft ausschliesslich das ausfuehrbare Jar
# (die Datei *.jar.original endet nicht auf .jar und wird nicht kopiert).
COPY --from=build --chown=10001:10001 /build/target/*.jar /app/app.jar

# --- Laufzeit-Konfiguration (KEINE Secrets, nur unkritische Defaults) ---

# Produktions-Spring-Profil aktivieren. Alle sensiblen Werte kommen weiterhin
# ausschliesslich aus zur Laufzeit gesetzten Environment-Variablen.
ENV SPRING_PROFILES_ACTIVE=prod

# Container-bewusste JVM-Defaults: Heap an das Container-Memory-Limit koppeln.
# Ueberschreibbar durch Setzen von JAVA_OPTS beim Start.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

# Standard-Port zu Dokumentationszwecken. Die Anwendung bindet server.port=${PORT:8080};
# die Plattform kann per PORT-Environment-Variable einen anderen Port erzwingen.
EXPOSE 8080

# HINWEIS: Bewusst KEIN HEALTHCHECK. Das schlanke JRE-Basisimage enthaelt kein
# zuverlaessig verfuegbares HTTP-Werkzeug (curl/wget), auf das sich ein Docker-
# HEALTHCHECK stuetzen koennte. Die Orchestrierungs-/Hosting-Plattform soll die
# Anwendung stattdessen ueber einen HTTP-Readiness-/Liveness-Probe auf GET /health
# pruefen.

# Ab hier als Nicht-root-Benutzer laufen.
USER app

# Exec-Form ueber "sh -c", damit $JAVA_OPTS vom Shell expandiert wird; "exec" ersetzt
# die Shell durch den Java-Prozess, sodass dieser PID 1 wird und Signale
# (z. B. SIGTERM beim Stoppen) korrekt empfaengt.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
