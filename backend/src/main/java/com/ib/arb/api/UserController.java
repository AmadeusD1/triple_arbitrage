package com.ib.arb.api;

import com.ib.arb.model.User;
import com.ib.arb.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    private static final java.util.Set<String> VALID_ROLES = java.util.Set.of("ADMIN", "USER", "QUANT");

    public record UserResponse(Long id, String username, String role) {}
    public record CreateUserRequest(String username, String password, String role) {}

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserResponse> listUsers() {
        return userRepo.findAll().stream()
            .map(u -> new UserResponse(u.getId(), u.getUsername(), u.getRole()))
            .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> createUser(@RequestBody CreateUserRequest req) {
        if (userRepo.findByUsername(req.username()).isPresent())
            return ResponseEntity.badRequest().build();

        var role = (req.role() != null && VALID_ROLES.contains(req.role())) ? req.role() : "USER";

        var user = new User();
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setRole(role);
        var saved = userRepo.save(user);
        return ResponseEntity.ok(new UserResponse(saved.getId(), saved.getUsername(), saved.getRole()));
    }

    public record UpdateRoleRequest(String role) {}

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> updateRole(@PathVariable("id") Long id,
                                                   @RequestBody UpdateRoleRequest req) {
        if (req.role() == null || !VALID_ROLES.contains(req.role()))
            return ResponseEntity.badRequest().build();
        return userRepo.findById(id).map(user -> {
            user.setRole(req.role());
            var saved = userRepo.save(user);
            return ResponseEntity.ok(new UserResponse(saved.getId(), saved.getUsername(), saved.getRole()));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable("id") Long id,
                                           @AuthenticationPrincipal User currentUser) {
        if (currentUser.getId().equals(id))
            return ResponseEntity.badRequest().build();   // cannot delete yourself
        if (userRepo.findById(id).isEmpty())
            return ResponseEntity.notFound().build();
        userRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
