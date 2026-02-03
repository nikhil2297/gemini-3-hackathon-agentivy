package com.agentivy.backend.controller;

import com.agentivy.backend.tools.angular.AngularDevServerTool;
import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Test controller for verifying Angular Dev Server Tool functionality.
 *
 * Provides endpoints to test:
 * - Starting Angular dev server
 * - Stopping Angular dev server
 * - Checking compilation status
 */
@Slf4j
@RestController
@RequestMapping("/api/test/angular")
@RequiredArgsConstructor
public class AngularDevServerTestController {

    private final AngularDevServerTool devServerTool;

    /**
     * Start Angular dev server for a cloned repository
     *
     * POST /api/test/angular/start
     * Body: {
     *   "repoPath": "C:/Users/nikhi/AppData/Local/Temp/agentivy/angular-tour-of-heroes-1768202655143",
     *   "port": 4200
     * }
     */
    @PostMapping("/start")
    public Map<String, Object> startServer(@RequestBody Map<String, Object> request) {
        String repoPath = (String) request.get("repoPath");
        Integer port = request.containsKey("port") ? (Integer) request.get("port") : 4200;

        log.info("Testing Angular dev server start for: {} on port {}", repoPath, port);

        try {
            ImmutableMap<String, Object> result = devServerTool.prepareAndStartServer(repoPath, port)
                    .blockingGet();
            return new HashMap<>(result);
        } catch (Exception e) {
            log.error("Error starting server", e);
            return Map.of(
                "status", "error",
                "message", e.getMessage()
            );
        }
    }

    /**
     * Stop Angular dev server
     *
     * POST /api/test/angular/stop
     * Body: {
     *   "repoPath": "C:/Users/nikhi/AppData/Local/Temp/agentivy/angular-tour-of-heroes-1768202655143"
     * }
     */
    @PostMapping("/stop")
    public Map<String, Object> stopServer(@RequestBody Map<String, String> request) {
        String repoPath = request.get("repoPath");

        log.info("Testing Angular dev server stop for: {}", repoPath);

        try {
            ImmutableMap<String, Object> result = devServerTool.stopServer(repoPath)
                    .blockingGet();
            return new HashMap<>(result);
        } catch (Exception e) {
            log.error("Error stopping server", e);
            return Map.of(
                "status", "error",
                "message", e.getMessage()
            );
        }
    }

    /**
     * Get compilation status
     *
     * POST /api/test/angular/status
     * Body: {
     *   "repoPath": "C:/Users/nikhi/AppData/Local/Temp/agentivy/angular-tour-of-heroes-1768202655143"
     * }
     */
    @PostMapping("/status")
    public Map<String, Object> getStatus(@RequestBody Map<String, String> request) {
        String repoPath = request.get("repoPath");

        log.info("Testing Angular compilation status for: {}", repoPath);

        try {
            ImmutableMap<String, Object> result = devServerTool.getCompilationStatus(repoPath)
                    .blockingGet();
            return new HashMap<>(result);
        } catch (Exception e) {
            log.error("Error getting status", e);
            return Map.of(
                "status", "error",
                "message", e.getMessage()
            );
        }
    }

    /**
     * Get tool metadata
     *
     * GET /api/test/angular/info
     */
    @GetMapping("/info")
    public Map<String, Object> getToolInfo() {
        return Map.of(
            "metadata", devServerTool.getMetadata(),
            "defaultRepoPath", "C:/Users/nikhi/AppData/Local/Temp/agentivy/angular-tour-of-heroes-1768202655143"
        );
    }

    /**
     * Quick test endpoint with pre-filled repo path
     *
     * GET /api/test/angular/quick-test
     */
    @GetMapping("/quick-test")
    public Map<String, Object> quickTest() {
        String repoPath = "C:\\Users\\nikhi\\AppData\\Local\\Temp\\agentivy\\angular-tour-of-heroes-1768202655143";

        Map<String, Object> result = new HashMap<>();
        result.put("repoPath", repoPath);
        result.put("instructions", Map.of(
            "step1", "POST to /api/test/angular/start with this repoPath",
            "step2", "Wait for compilation to complete",
            "step3", "POST to /api/test/angular/status to check",
            "step4", "Open http://localhost:4200 in browser",
            "step5", "POST to /api/test/angular/stop when done"
        ));

        return result;
    }
}
