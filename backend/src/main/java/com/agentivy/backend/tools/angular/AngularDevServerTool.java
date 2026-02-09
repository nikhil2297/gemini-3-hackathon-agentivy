package com.agentivy.backend.tools.angular;

import com.agentivy.backend.service.EventPublisherHelper;
import com.agentivy.backend.service.SessionContext;
import com.agentivy.backend.tools.angular.*;
import com.agentivy.backend.tools.registry.ToolCategory;
import com.agentivy.backend.tools.registry.ToolMetadata;
import com.agentivy.backend.tools.registry.ToolProvider;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Maybe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AngularDevServerTool implements ToolProvider {

    private final AngularProcessManager processManager;
    private final AngularLogParser logParser;
    private final AngularProjectPatcher projectPatcher;
    private final PackageManagerDetector packageManagerDetector;
    private final EventPublisherHelper eventPublisher;

    private static final int DEFAULT_PORT = 4200;
    private static final int TIMEOUT_SECONDS = 180;

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "angular.devServer",
            "Angular Development Server",
            "Manages Angular development server lifecycle (start, stop, monitor compilation)",
            ToolCategory.ANGULAR_DEV,
            "1.0.0",
            true,
            List.of("angular", "dev-server", "ng-serve", "development", "compilation"),
            Map.of(
                "defaultPort", DEFAULT_PORT,
                "timeoutSeconds", TIMEOUT_SECONDS
            )
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(
                FunctionTool.create(this, "prepareAndStartServer"),
                FunctionTool.create(this, "stopServer"),
                FunctionTool.create(this, "getCompilationStatus")
        );
    }

    public Maybe<ImmutableMap<String, Object>> prepareAndStartServer(
            @Schema(name = "repoPath") String repoPath,
            @Schema(name = "port") int port) {

        return Maybe.fromCallable(() -> {
            int serverPort = port > 0 ? port : DEFAULT_PORT;
            Path projectPath = Path.of(repoPath);
            long startTime = System.currentTimeMillis();

            eventPublisher.publishToolCall("prepareAndStartServer", "Preparing Angular dev server on port " + serverPort);

            // Use current component name if available, otherwise use "DevServer"
            String componentName = SessionContext.getCurrentComponent() != null
                ? SessionContext.getCurrentComponent()
                : "DevServer";

            // Publish starting status
            eventPublisher.publishComponentStatus(
                componentName,
                "dev-server",
                "starting",
                "Initializing Angular development server...",
                Map.of(
                    "port", serverPort,
                    "projectPath", repoPath,
                    "timeoutSeconds", TIMEOUT_SECONDS
                )
            );

            // 1. Validation
            PackageManagerDetector.ValidationResult validation = packageManagerDetector.validate(projectPath);
            log.info("Project validation: {}", validation.valid() ? "PASSED" : "FAILED");

            eventPublisher.publishComponentStatus(
                componentName,
                "dev-server",
                "in-progress",
                "Validating Angular project...",
                Map.of(
                    "phase", "validation",
                    "validationPassed", validation.valid(),
                    "issuesFound", validation.valid() ? 0 : validation.issues().split("\n").length,
                    "progressPercent", 15
                )
            );

            if (!validation.valid()) {
                log.warn("Validation issues:\n{}", validation.issues());
                // Continue anyway - we'll try to fix issues during setup
            }

            // 2. Detect package manager
            PackageManagerDetector.PackageManager pm = packageManagerDetector.detect(projectPath);
            log.info("Detected package manager: {}", pm.name());

            eventPublisher.publishComponentStatus(
                componentName,
                "dev-server",
                "in-progress",
                "Detected package manager: " + pm.name(),
                Map.of(
                    "phase", "package-manager-detection",
                    "packageManager", pm.name(),
                    "installCommand", pm.getInstallCommand(),
                    "progressPercent", 25
                )
            );

            // 3. Setup
            try {
                projectPatcher.injectHarnessRoute(projectPath);

                eventPublisher.publishComponentStatus(
                    componentName,
                    "dev-server",
                    "in-progress",
                    "Installing dependencies with " + pm.name() + "...",
                    Map.of(
                        "phase", "dependency-installation",
                        "packageManager", pm.name(),
                        "progressPercent", 40
                    )
                );

                // Always install dependencies to ensure package.json changes are picked up
                log.info("Installing dependencies with {}...", pm.name());
                processManager.runNpmInstall(projectPath);

                eventPublisher.publishComponentStatus(
                    componentName,
                    "dev-server",
                    "in-progress",
                    "Dependencies installed successfully",
                    Map.of(
                        "phase", "dependency-installation-complete",
                        "progressPercent", 60
                    )
                );
            } catch (Exception e) {
                eventPublisher.publishComponentStatus(
                    componentName,
                    "dev-server",
                    "failed",
                    "Setup failed: " + e.getMessage(),
                    Map.of(
                        "phase", "setup",
                        "error", e.getMessage()
                    )
                );
                return errorResult("Setup failed: " + e.getMessage());
            }

            // 4. Find available port, then start server
            try {
                // Auto-find a free port if the requested one is occupied
                int originalPort = serverPort;
                int maxAttempts = 10;
                for (int attempt = 0; attempt < maxAttempts; attempt++) {
                    try (var testSocket = new java.net.ServerSocket(serverPort)) {
                        testSocket.setReuseAddress(true);
                        break; // Port is free
                    } catch (java.io.IOException portEx) {
                        log.warn("Port {} is already in use, trying {}", serverPort, serverPort + 1);
                        serverPort++;
                        if (attempt == maxAttempts - 1) {
                            log.error("No available port found in range {}-{}", originalPort, serverPort);
                            eventPublisher.publishComponentStatus(
                                componentName, "dev-server", "failed",
                                "No available port found in range " + originalPort + "-" + serverPort,
                                Map.of("port", originalPort, "error", "NO_PORT_AVAILABLE")
                            );
                            return errorResult("No available port found. Ports " + originalPort
                                + "-" + serverPort + " are all in use.");
                        }
                    }
                }
                if (serverPort != originalPort) {
                    log.info("Port {} was occupied, using port {} instead", originalPort, serverPort);
                }

                log.info("Starting Angular dev server on port {}...", serverPort);

                eventPublisher.publishComponentStatus(
                    componentName,
                    "dev-server",
                    "in-progress",
                    "Starting Angular dev server on port " + serverPort + "...",
                    Map.of(
                        "phase", "server-starting",
                        "port", serverPort,
                        "serverUrl", "http://localhost:" + serverPort,
                        "progressPercent", 70
                    )
                );

                processManager.startDevServer(repoPath, serverPort);
            } catch (Exception e) {
                eventPublisher.publishComponentStatus(
                    componentName,
                    "dev-server",
                    "failed",
                    "Failed to start server: " + e.getMessage(),
                    Map.of(
                        "phase", "server-start",
                        "error", e.getMessage()
                    )
                );
                return errorResult("Failed to start server: " + e.getMessage());
            }

            // 5. Monitor and wait for compilation
            String serverUrl = "http://localhost:" + serverPort;

            eventPublisher.publishComponentStatus(
                componentName,
                "dev-server",
                "in-progress",
                "Waiting for compilation to complete...",
                Map.of(
                    "phase", "compilation",
                    "serverUrl", serverUrl,
                    "progressPercent", 85
                )
            );

            ImmutableMap<String, Object> result = waitForServer(repoPath, serverUrl, projectPath);

            // Publish final status based on result
            if ("success".equals(result.get("status"))) {
                long timeElapsed = System.currentTimeMillis() - startTime;
                eventPublisher.publishComponentStatus(
                    componentName,
                    "dev-server",
                    "completed",
                    "Dev server ready and compilation successful",
                    Map.of(
                        "passed", true,
                        "serverUrl", serverUrl,
                        "harnessUrl", serverUrl + "/agent-ivy-harness",
                        "port", serverPort,
                        "timeElapsed", timeElapsed + "ms",
                        "compilationSuccess", true
                    )
                );
            } else {
                eventPublisher.publishComponentStatus(
                    componentName,
                    "dev-server",
                    "failed",
                    "Dev server failed: " + result.get("reason"),
                    Map.of(
                        "passed", false,
                        "reason", result.get("reason"),
                        "hasCompilationErrors", result.containsKey("compilationErrors")
                    )
                );
            }

            return result;
        });
    }

    public Maybe<ImmutableMap<String, Object>> stopServer(@Schema(name = "repoPath") String repoPath) {
        return Maybe.fromCallable(() -> {
            processManager.stopServer(repoPath);
            return ImmutableMap.of("status", "success", "message", "Server stopped");
        });
    }

    public Maybe<ImmutableMap<String, Object>> getCompilationStatus(@Schema(name = "repoPath") String repoPath) {
        return Maybe.fromCallable(() -> {
            String output = processManager.getServerOutput(repoPath);
            if (output.isEmpty()) return errorResult("No output available");

            boolean hasErrors = logParser.hasCompilationErrors(output);
            List<String> errors = logParser.extractErrors(output);

            return ImmutableMap.<String, Object>builder()
                    .put("status", "success")
                    .put("hasErrors", hasErrors)
                    .put("errors", errors)
                    .build();
        });
    }

    // --- Private Helper: The Waiting Logic ---

    private ImmutableMap<String, Object> waitForServer(String repoPath, String url, Path projectPath) {
        long deadline = System.currentTimeMillis() + (TIMEOUT_SECONDS * 1000L);
        long startTime = System.currentTimeMillis();
        long gracePeriodMs = 15000; // 15 seconds grace period before checking for errors
        int checkCount = 0;

        // Get component name for status updates
        String componentName = SessionContext.getCurrentComponent() != null
            ? SessionContext.getCurrentComponent()
            : "DevServer";

        log.info("=== waitForServer started ===");
        log.info("URL to check: {}", url);
        log.info("Grace period: {}ms", gracePeriodMs);
        log.info("Timeout: {}s", TIMEOUT_SECONDS);

        while (System.currentTimeMillis() < deadline) {
            checkCount++;
            long elapsed = System.currentTimeMillis() - startTime;
            boolean processAlive = processManager.isServerProcessAlive(repoPath);

            log.info("Check #{}: elapsed={}ms, processAlive={}", checkCount, elapsed, processAlive);

            if (!processAlive) {
                log.error("Process died unexpectedly! Getting final output...");
                String finalOutput = processManager.getServerOutput(repoPath);
                log.error("Final process output (last 1000 chars): {}",
                    finalOutput.length() > 1000 ? finalOutput.substring(finalOutput.length() - 1000) : finalOutput);
                return buildFailureResponse(repoPath, projectPath, "Process died unexpectedly");
            }

            String output = processManager.getServerOutput(repoPath);
            log.debug("Current output length: {} chars", output.length());

            // Log output snippet every 3 checks for debugging
            if (checkCount % 3 == 0 && !output.isEmpty()) {
                String snippet = output.length() > 500 ? output.substring(output.length() - 500) : output;
                log.info("Output snippet (last 500 chars): {}", snippet);
            }

            // Only check for errors AFTER grace period to avoid false positives from startup messages
            boolean hasErrors = logParser.hasCompilationErrors(output);
            boolean pastGracePeriod = elapsed > gracePeriodMs;

            log.debug("Error check: elapsed={}ms, pastGrace={}, hasErrors={}", elapsed, pastGracePeriod, hasErrors);

            if (pastGracePeriod && hasErrors) {
                log.error("Compilation errors detected after grace period!");
                log.error("Output causing error detection (last 1500 chars): {}",
                    output.length() > 1500 ? output.substring(output.length() - 1500) : output);
                try { Thread.sleep(2000); } catch (Exception ignored) {}
                return buildFailureResponse(repoPath, projectPath, "Compilation errors detected");
            }

            // Success: Check URL
            boolean urlReachable = checkUrl(url);
            log.debug("URL check: reachable={}", urlReachable);

            if (urlReachable) {
                log.info("SUCCESS! Server is ready at {} after {}ms", url, elapsed);
                return ImmutableMap.<String, Object>builder()
                        .put("status", "success")
                        .put("serverUrl", url)
                        .put("harnessUrl", url + "/agent-ivy-harness")
                        .build();
            }

            // Publish progress every 2 checks (4 seconds) to keep SSE connection alive
            if (checkCount % 2 == 0) {
                int elapsedSeconds = (int) (elapsed / 1000);
                eventPublisher.publishComponentStatus(
                    componentName,
                    "dev-server",
                    "in-progress",
                    "Waiting for Angular compilation... (" + elapsedSeconds + "s)",
                    Map.of(
                        "phase", "compilation",
                        "elapsedSeconds", elapsedSeconds,
                        "maxSeconds", TIMEOUT_SECONDS
                    )
                );
            }

            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
        }

        log.error("TIMEOUT waiting for server after {}s", TIMEOUT_SECONDS);
        return buildFailureResponse(repoPath, projectPath, "Timeout waiting for server");
    }

    private boolean checkUrl(String urlString) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            return conn.getResponseCode() >= 200;
        } catch (Exception e) {
            return false;
        }
    }

    private ImmutableMap<String, Object> buildFailureResponse(String repoPath, Path projectPath, String reason) {
        processManager.stopServer(repoPath); // Stop failed server
        String output = processManager.getServerOutput(repoPath);

        return ImmutableMap.<String, Object>builder()
                .put("status", "error")
                .put("reason", reason)
                .put("compilationErrors", logParser.extractErrors(output))
                .put("logs", output.length() > 2000 ? output.substring(output.length() - 2000) : output)
                .build();
    }

    private ImmutableMap<String, Object> errorResult(String msg) {
        return ImmutableMap.of("status", "error", "message", msg);
    }
}