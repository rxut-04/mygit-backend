package com.mygit.backend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping({"/", "/api", "/api/"})
    public Map<String, Object> welcome() {
        return Map.of(
            "service", "mygit-backend",
            "status", "ONLINE",
            "creator", "Om Raut (CDAC Pass Out Student)",
            "message", "Byte-Compatible Git & Platform REST API",
            "health", "/api/health"
        );
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "service", "mygit-backend",
            "version", "0.0.1-SNAPSHOT"
        );
    }
}
