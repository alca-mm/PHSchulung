package de.internal.awareness.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Liefert die eigene Thymeleaf-Loginseite (oeffentlich). Der eigentliche Login (POST /login), die
 * Fehlerbehandlung (/login?error), das Logout (POST /logout) und die Session verwaltet Spring Security.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
