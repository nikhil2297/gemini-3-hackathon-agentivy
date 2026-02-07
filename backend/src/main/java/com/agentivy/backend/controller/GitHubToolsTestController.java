package com.agentivy.backend.controller;

import com.agentivy.backend.tools.github.GitHubCloneTool;
import com.agentivy.backend.tools.github.GitHubComponentScannerTool;
import com.agentivy.backend.tools.github.GitHubFileReaderTool;
import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Test controller for verifying GitHub tools functionality.
 *
 * Provides endpoints to test:
 * - GitHubCloneTool
 * - GitHubComponentScannerTool
 * - GitHubFileReaderTool
 */
@Slf4j
@RestController
@RequestMapping("/api/test/github")
@RequiredArgsConstructor
public class GitHubToolsTestController {

    private final GitHubCloneTool cloneTool;
    private final GitHubComponentScannerTool scannerTool;
    private final GitHubFileReaderTool fileReaderTool;

    /**
     * Test the complete GitHub workflow: clone → scan → read
     *
     * GET /api/test/github/workflow?repoUrl=https://github.com/user/repo
     */
    @GetMapping("/workflow")
    public Map<String, Object> testCompleteWorkflow(
            @RequestParam String repoUrl) {

        Map<String, Object> result = new HashMap<>();
        result.put("repoUrl", repoUrl);

        try {
            // Step 1: Clone repository
            log.info("Testing clone for: {}", repoUrl);
            ImmutableMap<String, Object> cloneResult = cloneTool.cloneRepository(repoUrl)
                    .blockingGet();
            result.put("cloneResult", cloneResult);

            if (!"success".equals(cloneResult.get("status"))) {
                result.put("overallStatus", "failed_at_clone");
                return result;
            }

            String localPath = (String) cloneResult.get("localPath");

            // Step 2: Scan for components
            log.info("Testing scan for: {}", localPath);
            ImmutableMap<String, Object> scanResult = scannerTool.scanForAngularComponents(localPath)
                    .blockingGet();
            result.put("scanResult", scanResult);

            if (!"success".equals(scanResult.get("status"))) {
                result.put("overallStatus", "failed_at_scan");
                return result;
            }

            // Step 3: Read first component (if any)
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, String>> components =
                    (java.util.List<Map<String, String>>) scanResult.get("components");

            if (components != null && !components.isEmpty()) {
                Map<String, String> firstComponent = components.get(0);
                String tsPath = firstComponent.get("tsPath");
                String templatePath = firstComponent.getOrDefault("htmlPath", "");
                String stylesPath = firstComponent.getOrDefault("cssPath", "");

                log.info("Testing read for component: {}", firstComponent.get("name"));
                ImmutableMap<String, Object> readResult = fileReaderTool.readComponentFiles(
                        tsPath, templatePath, stylesPath
                ).blockingGet();
                result.put("readResult", readResult);

                if (!"success".equals(readResult.get("status"))) {
                    result.put("overallStatus", "failed_at_read");
                    return result;
                }
            } else {
                result.put("readResult", "No components found to read");
            }

            result.put("overallStatus", "success");
            result.put("message", "All GitHub tools working correctly!");

        } catch (Exception e) {
            log.error("Error testing GitHub workflow", e);
            result.put("overallStatus", "error");
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Test only the clone functionality
     *
     * POST /api/test/github/clone
     * Body: { "repoUrl": "https://github.com/user/repo" }
     */
    @PostMapping("/clone")
    public Map<String, Object> testClone(@RequestBody Map<String, String> request) {
        String repoUrl = request.get("repoUrl");
        log.info("Testing clone for: {}", repoUrl);

        try {
            ImmutableMap<String, Object> result = cloneTool.cloneRepository(repoUrl)
                    .blockingGet();
            return new HashMap<>(result);
        } catch (Exception e) {
            log.error("Error testing clone", e);
            return Map.of(
                "status", "error",
                "message", e.getMessage()
            );
        }
    }

    /**
     * Test only the scan functionality
     *
     * POST /api/test/github/scan
     * Body: { "localPath": "C:/path/to/repo" }
     */
    @PostMapping("/scan")
    public Map<String, Object> testScan(@RequestBody Map<String, String> request) {
        String localPath = request.get("localPath");
        log.info("Testing scan for: {}", localPath);

        try {
            ImmutableMap<String, Object> result = scannerTool.scanForAngularComponents(localPath)
                    .blockingGet();
            return new HashMap<>(result);
        } catch (Exception e) {
            log.error("Error testing scan", e);
            return Map.of(
                "status", "error",
                "message", e.getMessage()
            );
        }
    }

    /**
     * Test only the read functionality
     *
     * POST /api/test/github/read
     * Body: {
     *   "typescriptPath": "C:/path/to/component.ts",
     *   "templatePath": "C:/path/to/component.html",
     *   "stylesPath": "C:/path/to/component.scss"
     * }
     */
    @PostMapping("/read")
    public Map<String, Object> testRead(@RequestBody Map<String, String> request) {
        String tsPath = request.get("typescriptPath");
        String templatePath = request.getOrDefault("templatePath", "");
        String stylesPath = request.getOrDefault("stylesPath", "");

        log.info("Testing read for: {}", tsPath);

        try {
            ImmutableMap<String, Object> result = fileReaderTool.readComponentFiles(
                    tsPath, templatePath, stylesPath
            ).blockingGet();
            return new HashMap<>(result);
        } catch (Exception e) {
            log.error("Error testing read", e);
            return Map.of(
                "status", "error",
                "message", e.getMessage()
            );
        }
    }

    /**
     * Get metadata about all GitHub tools
     *
     * GET /api/test/github/info
     */
    @GetMapping("/info")
    public Map<String, Object> getToolsInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("cloneTool", cloneTool.getMetadata());
        info.put("scannerTool", scannerTool.getMetadata());
        info.put("fileReaderTool", fileReaderTool.getMetadata());
        return info;
    }
}
