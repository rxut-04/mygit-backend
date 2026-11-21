package com.mygit.backend.controller;

import com.mygit.backend.dto.AuthResponse;
import com.mygit.backend.dto.LoginReq;
import com.mygit.backend.dto.RegisterReq;
import com.mygit.backend.model.User;
import com.mygit.backend.repository.UserRepository;
import com.mygit.backend.security.CustomUser;
import com.mygit.backend.security.JwtUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private JwtUtil jwt;

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterReq r) {
        if (users.existsByEmail(r.email())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Email taken"));
        }
        if (users.existsByUsername(r.username())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Username taken"));
        }

        User u = new User();
        u.setUsername(r.username());
        u.setEmail(r.email());
        u.setPasswordHash(encoder.encode(r.password()));
        users.save(u);

        String token = jwt.create(u.getId(), u.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponse(token, u.getId(), u.getUsername(), u.getEmail()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginReq r) {
        User u = users.findByEmail(r.email()).orElse(null);
        if (u == null || !encoder.matches(r.password(), u.getPasswordHash())) {
            // Constant response against account enumeration attacks
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid credentials"));
        }

        String token = jwt.create(u.getId(), u.getUsername());
        return ResponseEntity.ok(new AuthResponse(token, u.getId(), u.getUsername(), u.getEmail()));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@AuthenticationPrincipal CustomUser principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        User u = users.findById(principal.getId()).orElse(null);
        if (u == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }
        return ResponseEntity.ok(Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "email", u.getEmail(),
                "createdAt", u.getCreatedAt()
        ));
    }
}
