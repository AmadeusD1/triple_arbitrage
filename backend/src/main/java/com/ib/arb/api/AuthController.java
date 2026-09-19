package com.ib.arb.api;

import com.ib.arb.alert.AlertService;
import com.ib.arb.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authManager;
    private final AlertService alertService;

    public AuthController(AuthenticationManager authManager, AlertService alertService) {
        this.authManager = authManager;
        this.alertService = alertService;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestBody LoginRequest req, HttpServletRequest httpReq) {
        var ip = clientIp(httpReq);

        org.springframework.security.core.Authentication auth;
        try {
            auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (AuthenticationException e) {
            alertService.loginFailed(req.username(), ip);
            return ResponseEntity.status(401).build();
        }

        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);

        var session = httpReq.getSession(true);
        session.setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);

        var user = (User) auth.getPrincipal();
        alertService.loginSucceeded(user.getUsername(), ip);
        return ResponseEntity.ok(Map.of("username", user.getUsername(), "role", user.getRole()));
    }

    /** nginx forwards the real client address via X-Forwarded-For (see arb.conf); every
     *  request otherwise arrives from nginx itself on localhost. */
    private static String clientIp(HttpServletRequest req) {
        var xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(401).build();
        }
        if (auth.getPrincipal() instanceof User user) {
            return ResponseEntity.ok(Map.of("username", user.getUsername(), "role", user.getRole()));
        }
        return ResponseEntity.status(401).build();
    }
}
