package com.mygit.backend.security;

import com.mygit.backend.model.PersonalAccessToken;
import com.mygit.backend.repository.PersonalAccessTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Component
public class JwtFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PersonalAccessTokenRepository patRepository;

    private static String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            String token = h.substring(7).trim();
            try {
                if (token.startsWith("mygit_pat_")) {
                    String hash = sha256Hex(token);
                    PersonalAccessToken pat = patRepository.findByTokenHashWithUser(hash).orElse(null);
                    if (pat != null && pat.getUser() != null) {
                        pat.setLastUsedAt(LocalDateTime.now());
                        patRepository.save(pat);
                        CustomUser u = new CustomUser(pat.getUser().getId(), pat.getUser().getUsername());
                        var auth = new UsernamePasswordAuthenticationToken(u, null, List.of());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } else {
                    CustomUser u = jwtUtil.parse(token);
                    var auth = new UsernamePasswordAuthenticationToken(u, null, List.of());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception e) {
                // Bad or expired token: stay unauthenticated
            }
        }
        chain.doFilter(req, res);
    }
}
