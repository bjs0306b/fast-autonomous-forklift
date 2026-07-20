package com.fast.backend.health.controller;

import com.fast.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private static final String SERVICE_NAME = "fast-backend";

    @GetMapping("/api/health")
    public ApiResponse<Map<String, String>> health() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("service", SERVICE_NAME);
        return ApiResponse.success(data);
    }
}
