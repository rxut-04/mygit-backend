package com.mygit.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

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

    /**
     * Verifies a Clerk session JWT against Clerk's JWKS endpoint and exchanges it for a mygit JWT,
     * auto-provisioning a mygit account for first-time Google/OAuth users.
     */
    @PostMapping("/clerk-exchange")
    public ResponseEntity<?> exchangeClerkSession(@RequestBody Map<String, String> body) {
        String clerkToken = body != null ? body.get("clerkToken") : null;
        String email = body != null && body.get("email") != null ? body.get("email").trim().toLowerCase() : "";
        String requestedUsername = body != null && body.get("username") != null ? body.get("username").trim() : "";

        if (clerkToken == null || clerkToken.isBlank() || email.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Missing Clerk session token or email"));
        }

        if (!verifyClerkJwt(clerkToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or expired Clerk session token"));
        }

        User u = users.findByEmail(email).orElse(null);
        if (u == null) {
            String base = !requestedUsername.isBlank()
                    ? requestedUsername.replaceAll("[^a-zA-Z0-9_-]", "").toLowerCase()
                    : email.split("@")[0].replaceAll("[^a-zA-Z0-9_-]", "").toLowerCase();
            if (base.length() < 3) base = "user" + base;
            if (base.length() > 24) base = base.substring(0, 24);
            String candidate = base;
            int suffix = 1;
            while (users.existsByUsername(candidate)) {
                candidate = base + suffix++;
            }

            u = new User();
            u.setUsername(candidate);
            u.setEmail(email);
            u.setPasswordHash(encoder.encode(UUID.randomUUID().toString()));
            u = users.save(u);
        }

        String token = jwt.create(u.getId(), u.getUsername());
        return ResponseEntity.ok(new AuthResponse(token, u.getId(), u.getUsername(), u.getEmail()));
    }

    private boolean verifyClerkJwt(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return false;

            ObjectMapper mapper = new ObjectMapper();
            Base64.Decoder urlDec = Base64.getUrlDecoder();
            JsonNode header = mapper.readTree(urlDec.decode(parts[0]));
            JsonNode payload = mapper.readTree(urlDec.decode(parts[1]));

            String kid = header.path("kid").asText("");
            String iss = payload.path("iss").asText("");
            long exp = payload.path("exp").asLong(0L);
            if (kid.isBlank() || iss.isBlank() || !iss.startsWith("https://")) return false;
            if (exp > 0 && Instant.now().getEpochSecond() > exp + 30) return false;

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(iss.replaceAll("/+$", "") + "/.well-known/jwks.json"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return false;

            JsonNode keys = mapper.readTree(resp.body()).path("keys");
            if (!keys.isArray()) return false;

            for (JsonNode k : keys) {
                if (kid.equals(k.path("kid").asText())) {
                    BigInteger n = new BigInteger(1, urlDec.decode(k.path("n").asText()));
                    BigInteger e = new BigInteger(1, urlDec.decode(k.path("e").asText()));
                    PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
                    Signature sig = Signature.getInstance("SHA256withRSA");
                    sig.initVerify(pub);
                    sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                    return sig.verify(urlDec.decode(parts[2]));
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
