package de.internal.awareness.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Statische "Deployment-Guard"-Tests (ohne Spring-Kontext): pruefen die Deployment-Artefakte im
 * Arbeitsverzeichnis des Moduls (Projektwurzel) inhaltlich - Dockerfile, .dockerignore, Production-Profil,
 * GitHub-Actions-Workflows und das Environment-Template.
 *
 * <p>Ziel: sicherstellen, dass keine Secrets ins Image/Repo/Pages-Artefakt gelangen, dass der Container als
 * Nicht-Root mit Java 21 laeuft, dass CI vor dem Image-Bau testet, dass die Workflows minimale Rechte haben und
 * dass das Pages-Artefakt ausschliesslich statische Dateien enthaelt und die Backend-URL aus einer oeffentlichen
 * Variable stammt (mit URL-Validierung).</p>
 */
class DeploymentArtifactsTest {

    private static String read(String relativePath) throws IOException {
        Path path = Path.of(relativePath);
        assertThat(Files.exists(path)).as("Deployment-Datei muss existieren: %s", relativePath).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    // --- Dockerfile ---------------------------------------------------------

    @Test
    void dockerfileIsMultiStageJava21NonRootWithDataVolumeAndNoSecrets() throws IOException {
        String df = read("Dockerfile");

        // Multi-Stage: JDK 21 zum Bauen, JRE 21 zur Laufzeit.
        assertThat(df).contains("FROM eclipse-temurin:21-jdk");
        assertThat(df).contains("FROM eclipse-temurin:21-jre");

        // Nicht-Root: ein dedizierter Benutzer wird angelegt und aktiviert (USER app, nicht root).
        assertThat(df).contains("USER app");
        assertThat(df).contains("10001");
        assertThat(df).doesNotContain("USER root");

        // Persistentes Datenvolume fuer SQLite.
        assertThat(df).contains("VOLUME");
        assertThat(df).contains("/data");

        // Tests werden im Image-Build NICHT ausgefuehrt (laufen autoritativ in der CI).
        assertThat(df).contains("-DskipTests");

        // Produktionsprofil als Default im Image.
        assertThat(df).contains("SPRING_PROFILES_ACTIVE=prod");

        // KEINE Secrets: keine ENV-Zeile setzt ein Passwort/Secret/Token auf einen Wert.
        assertThat(df).doesNotContainPattern("(?i)ENV\\s+\\S*(PASSWORD|SECRET|TOKEN)\\S*\\s*=\\s*\\S+");
        // Keine bekannten Klartext-Token-Praefixe.
        assertThat(df).doesNotContain("ghp_").doesNotContain("AKIA");
    }

    @Test
    void dockerignoreExcludesLocalDataAndSecretsButKeepsBuildInputs() throws IOException {
        String di = read(".dockerignore");

        // Lokale Daten/Secrets ausgeschlossen.
        assertThat(di).contains("/data/");
        assertThat(di).contains("*.db");
        assertThat(di).contains(".env");
        assertThat(di).contains("target/");

        // Build-Inputs NICHT ausgeschlossen (sonst schlaegt der Maven-Build im Image fehl). Zeilenbasiert
        // pruefen (Kommentare duerfen die Namen nennen; nur echte Ignore-Muster zaehlen). Kommentarzeilen (#)
        // werden ignoriert.
        java.util.List<String> patterns = di.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        assertThat(patterns)
                .as(".dockerignore darf pom.xml/mvnw/.mvn/src nicht als Muster ausschliessen")
                .noneMatch(l -> l.equals("pom.xml") || l.equals("/pom.xml")
                        || l.equals("mvnw") || l.equals("/mvnw") || l.equals("mvnw.cmd") || l.equals("/mvnw.cmd")
                        || l.equals(".mvn") || l.equals(".mvn/") || l.equals("/.mvn")
                        || l.equals("src") || l.equals("src/") || l.equals("/src") || l.equals("/src/"));
    }

    // --- Production-Profil --------------------------------------------------

    @Test
    void prodProfileHasSafeDefaultsAndNoSecrets() throws IOException {
        String p = read("src/main/resources/application-prod.properties");

        assertThat(p).contains("server.port=${PORT:8080}");
        assertThat(p).contains("app.data.dir=${APP_DATA_DIR:/data}");
        assertThat(p).contains("${app.data.dir}/app.db");
        assertThat(p).contains("server.forward-headers-strategy=framework");
        assertThat(p).contains("server.servlet.session.cookie.secure=true");
        assertThat(p).contains("server.error.include-stacktrace=never");

        // Live-Versand wird NICHT aktiviert (sicherer Default false bleibt bestehen).
        assertThat(p).doesNotContain("app.mail.live-send-enabled=true");

        // KEINE Secrets: Admin-/Mail-Passwoerter werden hier nicht gesetzt; nur ${...}-Referenzen sind erlaubt.
        assertThat(p).doesNotContain("app.admin.password=");
        assertThat(p).doesNotContainPattern("(?i)(password|secret)\\s*=\\s*(?!\\$\\{)\\S+");
    }

    // --- GitHub-Actions: CI ------------------------------------------------

    @Test
    void ciWorkflowUsesJava21AndRunsTestsWithMinimalPermissions() throws IOException {
        String ci = read(".github/workflows/backend-ci.yml");
        assertThat(ci).contains("java-version: '21'");
        assertThat(ci).contains("./mvnw -B test");
        assertThat(ci).contains("permissions:");
        assertThat(ci).contains("contents: read");
        assertNoWorkflowSecurityFootguns(ci);
    }

    // --- GitHub-Actions: Image (GHCR) --------------------------------------

    @Test
    void imageWorkflowTestsBeforeImageUsesGhcrTokenAndMinimalPermissions() throws IOException {
        String img = read(".github/workflows/backend-image.yml");

        // Image-Job haengt vom Test-Job ab -> Docker-Build nur nach erfolgreichen Tests.
        assertThat(img).contains("needs: test");
        assertThat(img).contains("./mvnw -B test");

        // GHCR + built-in GITHUB_TOKEN (kein PAT), minimale Rechte.
        assertThat(img).contains("ghcr.io");
        assertThat(img).contains("secrets.GITHUB_TOKEN");
        assertThat(img).contains("packages: write");
        assertThat(img).contains("java-version: '21'");
        // Kein Personal Access Token erzwungen.
        assertThat(img).doesNotContainPattern("(?i)secrets\\.(GH_PAT|PAT|CR_PAT|PERSONAL)");
        assertNoWorkflowSecurityFootguns(img);
    }

    // --- GitHub-Actions: Pages ---------------------------------------------

    @Test
    void pagesWorkflowShipsOnlyStaticFilesValidatesUrlAndLeaksNoBackendCode() throws IOException {
        String pg = read(".github/workflows/pages.yml");

        // Offizieller Pages-Flow.
        assertThat(pg).contains("upload-pages-artifact");
        assertThat(pg).contains("deploy-pages");
        assertThat(pg).contains("pages: write");

        // Nur statische Dateien werden ins Artefakt kopiert (inkl. des vom oeffentlichen index.html
        // referenzierten Root-styles.css).
        assertThat(pg).contains("cp index.html");
        assertThat(pg).contains("cp styles.css");
        assertThat(pg).contains("admin");
        assertThat(pg).contains("assets");
        // KEIN Backend-Code / keine sensiblen Dateien werden ins Pages-Artefakt kopiert.
        assertThat(pg).doesNotContain("cp -R src");
        assertThat(pg).doesNotContain("cp pom.xml");
        assertThat(pg).doesNotContain("cp -R target");

        // Backend-URL kommt aus einer oeffentlichen VARIABLE (kein Secret) und wird validiert.
        assertThat(pg).contains("vars.BACKEND_BASE_URL");
        assertThat(pg).doesNotContain("secrets.BACKEND_BASE_URL");
        assertThat(pg).contains("https://");
        assertThat(pg).contains("exit 1"); // ungueltige Produktions-URL -> Deploy schlaegt fehl
        assertNoWorkflowSecurityFootguns(pg);
    }

    // --- GitHub-Actions: Deploy-Platzhalter --------------------------------

    @Test
    void deployWorkflowIsManualOnlyAndDoesNotFakeDeploy() throws IOException {
        String dp = read(".github/workflows/deploy-backend.yml");
        assertThat(dp).contains("workflow_dispatch");
        assertThat(dp).contains("contents: read");
        // Kein echter Deploy (kein docker push, kein scp/ssh-Deploy) im Platzhalter.
        assertThat(dp).doesNotContainPattern("(?i)docker\\s+push");
        assertThat(dp).doesNotContainPattern("(?i)\\bscp\\b|\\bssh\\b|appleboy/ssh");
        assertNoWorkflowSecurityFootguns(dp);
    }

    // --- Environment-Template ----------------------------------------------

    @Test
    void environmentTemplateListsNamesWithoutRealSecrets() throws IOException {
        String env = read("docs/deployment/environment.example");

        assertThat(env).contains("SPRING_PROFILES_ACTIVE=prod");
        assertThat(env).contains("APP_ADMIN_USERNAME=");
        assertThat(env).contains("APP_ADMIN_PASSWORD=");
        assertThat(env).contains("MAIL_PASSWORD=");
        assertThat(env).contains("APP_MAIL_LIVE_SEND_ENABLED=false");
        assertThat(env).contains("APP_API_ALLOWED_ORIGINS=https://alca-mm.github.io");

        // Secret-Namen duerfen KEINEN Wert haben (Zeile endet direkt nach '=').
        assertThat(env.lines().map(String::trim))
                .as("APP_ADMIN_PASSWORD darf keinen Wert enthalten")
                .anyMatch(l -> l.equals("APP_ADMIN_PASSWORD="))
                .noneMatch(l -> l.startsWith("APP_ADMIN_PASSWORD=") && !l.equals("APP_ADMIN_PASSWORD="));
        assertThat(env.lines().map(String::trim))
                .as("MAIL_PASSWORD darf keinen Wert enthalten")
                .noneMatch(l -> l.startsWith("MAIL_PASSWORD=") && !l.equals("MAIL_PASSWORD="));
    }

    // --- gemeinsame Workflow-Sicherheitschecks -----------------------------

    /** Prueft die wichtigsten Workflow-Footguns: kein pull_request_target, kein Secret-Echo. */
    private static void assertNoWorkflowSecurityFootguns(String workflow) {
        assertThat(workflow)
                .as("kein pull_request_target (untrusted Kontext mit Secrets)")
                .doesNotContain("pull_request_target");
        // Kein Ausgeben von Secrets in Logs (echo ... ${{ secrets... }}).
        assertThat(workflow)
                .as("kein echo eines Secrets")
                .doesNotContainPattern("(?i)echo[^\\n]*\\$\\{\\{\\s*secrets\\.");
    }
}
