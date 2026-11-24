package com.mygit.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtFilter jwtFilter) throws Exception {
        return http
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(c -> c.disable())
                .headers(h -> h.frameOptions(f -> f.disable())) // Allows H2 console if enabled
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/", "/api", "/api/").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/repos/*/refs", "/api/repos/*/*/refs").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/repos/*/objects/**", "/api/repos/*/*/objects/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/repos/*/files", "/api/repos/*/*/files").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/repos/*/commits", "/api/repos/*/*/commits").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/repos/*/branches", "/api/repos/*/*/branches").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/repos/*/archive/**", "/api/repos/*/*/archive/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/repos/*/pulls/**", "/api/repos/*/*/pulls/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/repos/*/commits/**", "/api/repos/*/*/commits/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/repos/*/issues/**", "/api/repos/*/*/issues/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/repos/*/star", "/api/repos/*/*/star").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/users/*/contributions", "/api/user/contributions").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://*.netlify.app",
                "https://velvety-druid-dccd85.netlify.app",
                "https://*.onrender.com"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With", "Origin"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
