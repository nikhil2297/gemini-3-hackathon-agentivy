package com.agentivy.backend.controller;

import com.agentivy.backend.tools.angular.AngularDevServerTool;
import com.agentivy.backend.tools.harness.metadata.ComponentMetadataExtractorTool;
import com.agentivy.backend.tools.harness.generator.HarnessCodeGeneratorTool;
import com.agentivy.backend.tools.harness.deployer.HarnessDeployerTool;
import com.agentivy.backend.tools.github.GitHubCloneTool;
import com.agentivy.backend.tools.github.GitHubComponentScannerTool;
import com.agentivy.backend.tools.github.GitHubFileReaderTool;
import com.agentivy.backend.tools.PlaywrightTools;
import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Test Controller - Direct access to tools for testing
 *
 * Use this to verify each tool works before running the full agent.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TestController {

    private final GitHubCloneTool gitHubCloneTool;
    private final GitHubComponentScannerTool gitHubComponentScannerTool;
    private final GitHubFileReaderTool gitHubFileReaderTool;
    private final ComponentMetadataExtractorTool metadataExtractor;
    private final HarnessCodeGeneratorTool codeGenerator;
    private final HarnessDeployerTool harnessDeployer;
    private final AngularDevServerTool angularDevServerTool;
    private final PlaywrightTools playwrightTools;

    // ==================== GITHUB TOOLS ====================

    /**
     * Clone a GitHub repository.
     *
     * POST /api/v1/test/clone
     * { "repoUrl": "https://github.com/user/repo" }
     */
    @PostMapping("/clone")
    public ResponseEntity<ImmutableMap<String, Object>> cloneRepo(@RequestBody Map<String, String> request) {
        String repoUrl = request.get("repoUrl");

        if (repoUrl == null || repoUrl.isBlank()) {
            return ResponseEntity.badRequest().body(ImmutableMap.of(
                    "status", "error",
                    "message", "Missing 'repoUrl'"
            ));
        }

        log.info("Cloning repository: {}", repoUrl);
        ImmutableMap<String, Object> result = gitHubCloneTool.cloneRepository(repoUrl).blockingGet();
        return ResponseEntity.ok(result);
    }

    /**
     * Scan for Angular components.
     *
     * POST /api/v1/test/scan
     * { "localPath": "/tmp/agentivy/repo-123" }
     */
    @PostMapping("/scan")
    public ResponseEntity<ImmutableMap<String, Object>> scanComponents(@RequestBody Map<String, String> request) {
        String localPath = request.get("localPath");

        if (localPath == null || localPath.isBlank()) {
            return ResponseEntity.badRequest().body(ImmutableMap.of(
                    "status", "error",
                    "message", "Missing 'localPath'"
            ));
        }

        log.info("Scanning for components: {}", localPath);
        ImmutableMap<String, Object> result = gitHubComponentScannerTool.scanForAngularComponents(localPath).blockingGet();
        return ResponseEntity.ok(result);
    }

    /**
     * Read component files.
     *
     * POST /api/v1/test/read
     * { "typescriptPath": "...", "templatePath": "...", "stylesPath": "..." }
     */
    @PostMapping("/read")
    public ResponseEntity<ImmutableMap<String, Object>> readFiles(@RequestBody Map<String, String> request) {
        String tsPath = request.get("typescriptPath");
        String htmlPath = request.getOrDefault("templatePath", "");
        String cssPath = request.getOrDefault("stylesPath", "");

        if (tsPath == null || tsPath.isBlank()) {
            return ResponseEntity.badRequest().body(ImmutableMap.of(
                    "status", "error",
                    "message", "Missing 'typescriptPath'"
            ));
        }

        log.info("Reading component files: {}", tsPath);
        ImmutableMap<String, Object> result = gitHubFileReaderTool.readComponentFiles(tsPath, htmlPath, cssPath).blockingGet();
        return ResponseEntity.ok(result);
    }

    // ==================== COMPONENT HARNESS (Atomic Tools) ====================

    /**
     * Create component test harness using the atomic tool pipeline:
     * 1. Extract metadata  2. Generate harness code  3. Deploy harness
     *
     * POST /api/v1/test/harness
     * {
     *   "repoPath": "/tmp/agentivy/repo-123",
     *   "componentClassName": "TaskListComponent"
     * }
     */
    @PostMapping("/harness")
    public ResponseEntity<ImmutableMap<String, Object>> createHarness(@RequestBody Map<String, String> request) {
        String componentClassName = request.get("componentClassName");
        String repoPath = request.getOrDefault("repoPath", "");

        if (componentClassName == null || componentClassName.isBlank()) {
            return ResponseEntity.badRequest().body(ImmutableMap.of(
                    "status", "error",
                    "message", "Missing 'componentClassName'"
            ));
        }

        log.info("Creating harness for: {}", componentClassName);

        try {
            // Step 1: Extract metadata
            ImmutableMap<String, Object> metadata = metadataExtractor
                .extractComponentMetadata(repoPath, componentClassName)
                .blockingGet();

            if ("error".equals(metadata.get("status"))) {
                return ResponseEntity.badRequest().body(metadata);
            }

            String selector = (String) metadata.get("selector");
            String importPath = (String) metadata.get("importPath");

            // Step 2: Generate harness code
            ImmutableMap<String, Object> codeResult = codeGenerator
                .generateHarnessCode(componentClassName, selector, importPath, "", "", "", "")
                .blockingGet();

            if ("error".equals(codeResult.get("status"))) {
                return ResponseEntity.badRequest().body(codeResult);
            }

            // Step 3: Deploy harness
            String generatedCode = (String) codeResult.get("code");
            ImmutableMap<String, Object> deployResult = harnessDeployer
                .deployHarness(repoPath, generatedCode)
                .blockingGet();

            return ResponseEntity.ok(deployResult);
        } catch (Exception e) {
            log.error("Harness creation failed", e);
            return ResponseEntity.internalServerError().body(ImmutableMap.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    // ==================== ANGULAR DEV SERVER ====================

    /**
     * Prepare and start Angular dev server.
     *
     * POST /api/v1/test/server/start
     * { "repoPath": "/tmp/agentivy/repo-123", "port": 4200 }
     */
    @PostMapping("/server/start")
    public ResponseEntity<ImmutableMap<String, Object>> startServer(@RequestBody Map<String, Object> request) {
        String repoPath = (String) request.get("repoPath");
        int port = request.containsKey("port") ? ((Number) request.get("port")).intValue() : 4200;

        if (repoPath == null || repoPath.isBlank()) {
            return ResponseEntity.badRequest().body(ImmutableMap.of(
                    "status", "error",
                    "message", "Missing 'repoPath'"
            ));
        }

        log.info("Starting Angular dev server: {} on port {}", repoPath, port);
        ImmutableMap<String, Object> result = angularDevServerTool.prepareAndStartServer(repoPath, port).blockingGet();
        return ResponseEntity.ok(result);
    }

    /**
     * Stop Angular dev server.
     *
     * POST /api/v1/test/server/stop
     * { "repoPath": "/tmp/agentivy/repo-123" }
     */
    @PostMapping("/server/stop")
    public ResponseEntity<ImmutableMap<String, Object>> stopServer(@RequestBody Map<String, String> request) {
        String repoPath = request.get("repoPath");

        if (repoPath == null || repoPath.isBlank()) {
            return ResponseEntity.badRequest().body(ImmutableMap.of(
                    "status", "error",
                    "message", "Missing 'repoPath'"
            ));
        }

        log.info("Stopping server for: {}", repoPath);
        ImmutableMap<String, Object> result = angularDevServerTool.stopServer(repoPath).blockingGet();
        return ResponseEntity.ok(result);
    }

    /**
     * Check server status.
     *
     * GET /api/v1/test/server/status?url=http://localhost:4200
     */
//    @GetMapping("/server/status")
//    public ResponseEntity<ImmutableMap<String, Object>> checkServerStatus(@RequestParam String url) {
//        log.info("Checking server status: {}", url);
//        ImmutableMap<String, Object> result = angularDevServerTool.checkServerStatus(url).blockingGet();
//        return ResponseEntity.ok(result);
//    }

    /**
     * DIAGNOSTIC: Test ng serve startup with full output capture (30 seconds)
     *
     * POST /api/v1/test/server/diagnostic
     * {
     *   "repoPath": "/path/to/angular/project",
     *   "port": 4200
     * }
     *
     * Returns: Full stdout/stderr output to diagnose why server won't start
     */
    @PostMapping("/server/diagnostic")
    public ResponseEntity<ImmutableMap<String, Object>> diagnosticTestServer(@RequestBody Map<String, Object> request) {
        String repoPath = (String) request.get("repoPath");
        int port = request.containsKey("port") ? ((Number) request.get("port")).intValue() : 4200;

        if (repoPath == null || repoPath.isBlank()) {
            return ResponseEntity.badRequest().body(ImmutableMap.of(
                    "status", "error",
                    "message", "Missing 'repoPath'"
            ));
        }

        log.info("=== DIAGNOSTIC TEST: Starting ng serve test for {} on port {}", repoPath, port);

        try {
            Path projectPath = Path.of(repoPath);
            if (!Files.exists(projectPath.resolve("angular.json"))) {
                return ResponseEntity.ok(ImmutableMap.of(
                        "status", "error",
                        "message", "Not an Angular project: angular.json not found"
                ));
            }

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            String command = String.format("npx ng serve --port %d --host 0.0.0.0", port);
            log.info("Running command: {}", command);

            ProcessBuilder pb = new ProcessBuilder()
                    .command(System.getProperty("os.name").toLowerCase().contains("win") ? "cmd" : "sh",
                            System.getProperty("os.name").toLowerCase().contains("win") ? "/c" : "-c",
                            command)
                    .directory(projectPath.toFile())
                    .redirectErrorStream(false);

            Process process = pb.start();

            // Capture stdout
            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                        log.info("[STDOUT] {}", line);
                    }
                } catch (Exception e) {
                    log.debug("Error reading stdout", e);
                }
            });

            // Capture stderr
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                        log.error("[STDERR] {}", line);
                    }
                } catch (Exception e) {
                    log.debug("Error reading stderr", e);
                }
            });

            stdoutThread.setDaemon(true);
            stderrThread.setDaemon(true);
            stdoutThread.start();
            stderrThread.start();

            // Test for 30 seconds
            long testStart = System.currentTimeMillis();
            boolean serverResponsive = false;
            int responseCode = 0;

            while (System.currentTimeMillis() - testStart < 30000) {
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL("http://localhost:" + port).openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    responseCode = conn.getResponseCode();
                    conn.disconnect();
                    serverResponsive = true;
                    log.info("SUCCESS! Server responded with code: {}", responseCode);
                    break;
                } catch (Exception e) {
                    log.debug("Server not responsive yet: {}", e.getMessage());
                }
                Thread.sleep(2000);
            }

            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);

            String stdoutStr = stdout.toString();
            String stderrStr = stderr.toString();

            return ResponseEntity.ok(ImmutableMap.<String, Object>builder()
                    .put("status", "success")
                    .put("testResult", serverResponsive ? "SERVER_UP" : "SERVER_FAILED")
                    .put("testDurationSeconds", 30)
                    .put("serverResponsive", serverResponsive)
                    .put("responseCode", serverResponsive ? responseCode : "N/A")
                    .put("stdout_preview", stdoutStr.substring(0, Math.min(2000, stdoutStr.length())))
                    .put("stderr_preview", stderrStr.substring(0, Math.min(2000, stderrStr.length())))
                    .put("stdout_length", stdoutStr.length())
                    .put("stderr_length", stderrStr.length())
                    .put("command", command)
                    .put("projectPath", projectPath.toString())
                    .build());

        } catch (Exception e) {
            log.error("Diagnostic test failed", e);
            return ResponseEntity.ok(ImmutableMap.of(
                    "status", "error",
                    "message", e.getMessage(),
                    "exception", e.getClass().getSimpleName()
            ));
        }
    }

    // ==================== PLAYWRIGHT ====================

    /**
     * Run accessibility audit.
     *
     * POST /api/v1/test/audit
     * {
     *   "componentUrl": "http://localhost:4200/agent-ivy-harness",
     *   "componentSelector": "#test-wrapper-123",
     *   "wcagLevel": "AA"
     * }
     */
    @PostMapping("/audit")
    public ResponseEntity<ImmutableMap<String, Object>> runAudit(@RequestBody Map<String, String> request) {
        String url = request.get("componentUrl");
        String selector = request.get("componentSelector");
        String level = request.getOrDefault("wcagLevel", "AA");

        if (url == null || selector == null) {
            return ResponseEntity.badRequest().body(ImmutableMap.of(
                    "status", "error",
                    "message", "Missing 'componentUrl' or 'componentSelector'"
            ));
        }

        log.info("Running accessibility audit: {} @ {}", selector, url);
        ImmutableMap<String, Object> result = playwrightTools.runAccessibilityAudit(url, selector, level).blockingGet();
        return ResponseEntity.ok(result);
    }

    // ==================== HEALTH CHECK ====================

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "tools", List.of(
                        "cloneRepository",
                        "scanForAngularComponents",
                        "readComponentFiles",
                        "loadComponentForTesting",
                        "prepareAndStartServer",
                        "stopServer",
                        "checkServerStatus",
                        "runAccessibilityAudit"
                ),
                "endpoints", Map.of(
                        "clone", "POST /api/v1/test/clone",
                        "scan", "POST /api/v1/test/scan",
                        "read", "POST /api/v1/test/read",
                        "harness", "POST /api/v1/test/harness",
                        "startServer", "POST /api/v1/test/server/start",
                        "stopServer", "POST /api/v1/test/server/stop",
                        "serverStatus", "GET /api/v1/test/server/status?url=...",
                        "audit", "POST /api/v1/test/audit"
                )
        ));
    }
}

