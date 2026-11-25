package com.mygit.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;
    private static final int AUTH_LIMIT = 30;
    private static final int PUSH_LIMIT = 60;

    private static class Bucket {
        long windowStart;
        final AtomicInteger count = new AtomicInteger(0);

        Bucket(long now) {
            this.windowStart = now;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        int limit = 0;
        String group = null;
        if (path.startsWith("/api/auth/")) {
            limit = AUTH_LIMIT;
            group = "auth";
        } else if (path.endsWith("/push") && "POST".equalsIgnoreCase(request.getMethod())) {
            limit = PUSH_LIMIT;
            group = "push";
        }

        if (group != null) {
            String ip = extractClientIp(request);
            String key = group + ":" + ip;
            long now = System.currentTimeMillis();

            Bucket bucket = buckets.compute(key, (k, existing) -> {
                if (existing == null || now - existing.windowStart >= WINDOW_MS) {
                    return new Bucket(now);
                }
                return existing;
            });

            if (bucket.count.incrementAndGet() > limit) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Too many requests. Please try again in a minute.\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
