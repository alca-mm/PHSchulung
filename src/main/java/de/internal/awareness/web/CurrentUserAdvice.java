package de.internal.awareness.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;

/**
 * Stellt allen Views den angemeldeten Admin-Benutzernamen als Modellattribut {@code adminUsername} bereit
 * (fuer die Anzeige in der Navigation). Bewusst ohne zusaetzliche Thymeleaf-Security-Abhaengigkeit.
 *
 * <p>Fuer nicht authentifizierte/anonyme Anfragen liefert {@link Principal} {@code null} (Spring Security gibt
 * fuer anonyme Anfragen keinen User-Principal zurueck), sodass Trainingsseiten o. ae. keinen Benutzernamen
 * zeigen. Es werden keine sensiblen Daten (Passwoerter/Rollen-Details) ausgegeben - nur der Anzeigename.</p>
 */
@ControllerAdvice
public class CurrentUserAdvice {

    @ModelAttribute("adminUsername")
    public String adminUsername(Principal principal) {
        return principal == null ? null : principal.getName();
    }
}
