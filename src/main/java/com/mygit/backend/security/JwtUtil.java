package com.mygit.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expirationMs = 86400000L; // 24 hours

    public JwtUtil(@Value("${jwt.secret:mygit-super-secret-key-that-is-at-least-32-bytes-long!}") String secret) {
        String envSecret = System.getenv("JWT_SECRET");
        String finalSecret = (envSecret != null && !envSecret.isBlank()) ? envSecret : secret;
        if (finalSecret.length() < 32) {
            finalSecret = String.format("%-32s", finalSecret).replace(' ', 'x');
        }
        this.key = Keys.hmacShaKeyFor(finalSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String create(Long userId, String username) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    public CustomUser parse(String token) {
        Claims c = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new CustomUser(Long.valueOf(c.getSubject()), c.get("username", String.class));
    }
}
