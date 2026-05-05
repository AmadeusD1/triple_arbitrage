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

    public record UserResponse(Long id, String username, String role) {}
    public record CreateUserRequest(String username, String password) {}

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

        var user = new User();
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setRole("USER");
        var saved = userRepo.save(user);
        return ResponseEntity.ok(new UserResponse(saved.getId(), saved.getUsername(), saved.getRole()));
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
