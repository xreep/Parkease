package com.smartparking.common;

import com.smartparking.common.seed.DemoMode;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private final DemoMode demoMode;

    @GetMapping
    public Map<String, Object> health() {
        // Static and database-free (Render's liveness check). demoMode lets the frontend show its demo banner.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("demoMode", demoMode.enabled());
        return body;
    }
}
