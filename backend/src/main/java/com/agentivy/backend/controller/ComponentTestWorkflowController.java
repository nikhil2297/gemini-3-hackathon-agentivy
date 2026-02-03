package com.agentivy.backend.controller;

import com.agentivy.backend.tools.harness.deployer.HarnessDeployerTool;
import com.agentivy.backend.tools.harness.generator.HarnessCodeGeneratorTool;
import com.agentivy.backend.tools.harness.metadata.ComponentMetadataExtractorTool;
import com.agentivy.backend.tools.angular.AngularDevServerTool;
import com.agentivy.backend.tools.testing.AccessibilityTestingTool;
import com.agentivy.backend.tools.testing.ComponentPerformanceTool;
import com.agentivy.backend.tools.fixing.AccessibilityFixerTool;
import com.agentivy.backend.tools.fixing.PerformanceFixerTool;
import com.agentivy.backend.service.SseEventPublisher;
import com.agentivy.backend.service.SessionContext;
import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Complete workflow API for component testing.
 * Single endpoint that orchestrates the entire testing process.
 */
@Slf4j
@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class ComponentTestWorkflowController {

    private final ComponentMetadataExtractorTool metadataExtractor;
    private final HarnessCodeGeneratorTool codeGenerator;
    private final HarnessDeployerTool deployer;
    private final AngularDevServerTool devServer;
    private final AccessibilityTestingTool accessibilityTester;
    private final ComponentPerformanceTool performanceTester;
    private final AccessibilityFixerTool accessibilityFixer;
    private final PerformanceFixerTool performanceFixer;
    private final SseEventPublisher sseEventPublisher;

    @PostMapping("/test-component")
    public ResponseEntity<Map<String, Object>> testComponent(@RequestBody TestComponentRequest request) {
        log.info("Starting component test workflow for: {}", request.componentClassName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("componentClassName", request.componentClassName);
        result.put("timestamp", System.currentTimeMillis());

        try {
            // Step 1: Extract Component Metadata
            log.info("Step 1: Extracting component metadata...");
            ImmutableMap<String, Object> metadataResult = metadataExtractor
                .extractComponentMetadata(request.repoPath, request.componentClassName)
                .blockingGet();

            if ("error".equals(metadataResult.get("status"))) {
                result.put("status", "error");
                result.put("step", "metadata_extraction");
                result.put("error", metadataResult.get("message"));
                return ResponseEntity.badRequest().body(result);
            }

            result.put("metadata", metadataResult);
                log.info("✓ Metadata extracted: selector={}", metadataResult.get("selector"));

            // Step 2: Generate Harness Code with LLM
            log.info("Step 2: Generating harness code with LLM...");
            String componentSelector = (String) metadataResult.get("selector");
            String importPath = (String) metadataResult.get("importPath");

            ImmutableMap<String, Object> codeResult = codeGenerator
                .generateHarnessCode(
                    request.componentClassName,
                    componentSelector,
                    importPath,
                    request.componentInputs != null ? request.componentInputs : "",
                    request.mockData != null ? request.mockData : "",
                    request.serviceMocks != null ? request.serviceMocks : "",
                    ""
                )
                .blockingGet();

            if ("error".equals(codeResult.get("status"))) {
                result.put("status", "error");
                result.put("step", "code_generation");
                result.put("error", codeResult.get("message"));
                return ResponseEntity.badRequest().body(result);
            }

            String generatedCode = (String) codeResult.get("code");
            result.put("harnessCodeGenerated", true);
            result.put("codeLength", generatedCode.length());
            log.info("✓ Harness code generated ({} chars)", generatedCode.length());

            // Step 3: Deploy Harness
            log.info("Step 3: Deploying harness to project...");
            ImmutableMap<String, Object> deployResult = deployer
                .deployHarness(request.repoPath, generatedCode)
                .blockingGet();

            if ("error".equals(deployResult.get("status"))) {
                result.put("status", "error");
                result.put("step", "deployment");
                result.put("error", deployResult.get("message"));
                return ResponseEntity.badRequest().body(result);
            }

            result.put("harnessDeployed", true);
            result.put("harnessFilePath", deployResult.get("harnessFilePath"));
            result.put("harnessUrl", deployResult.get("harnessUrl"));
            log.info("✓ Harness deployed to: {}", deployResult.get("harnessFilePath"));

            // Step 4: Start Angular Dev Server
            log.info("Step 4: Starting Angular dev server...");
            int port = request.port != null ? request.port : 4200;
            ImmutableMap<String, Object> serverResult = devServer
                .prepareAndStartServer(request.repoPath, port)
                .blockingGet();

            if ("error".equals(serverResult.get("status"))) {
                result.put("status", "error");
                result.put("step", "server_start");
                result.put("error", serverResult.get("reason"));
                result.put("compilationErrors", serverResult.get("compilationErrors"));
                return ResponseEntity.badRequest().body(result);
            }

            result.put("serverStarted", true);
            result.put("serverUrl", serverResult.get("serverUrl"));
            result.put("componentUrl", serverResult.get("harnessUrl"));
            log.info("✓ Server started at: {}", serverResult.get("serverUrl"));

            // Step 5: Run Tests (Continue even if individual tests fail)
            log.info("Step 5: Running tests...");
            Map<String, Object> testResults = new HashMap<>();

            String componentUrl = (String) serverResult.get("harnessUrl");
            boolean anyTestCompleted = false;

            // Run Accessibility Tests (with timeout protection)
            if (request.tests != null && request.tests.contains("accessibility")) {
                log.info("  → Running accessibility tests...");
                try {
                    // Use a separate thread with timeout to prevent blocking
                    ImmutableMap<String, Object> accessibilityResult = accessibilityTester
                        .runAccessibilityTest(componentUrl, "AA")
                        .timeout(90, java.util.concurrent.TimeUnit.SECONDS)  // 90 second timeout
                        .onErrorReturn(error -> ImmutableMap.of(
                            "status", "error",
                            "message", "Accessibility test timed out or failed: " + error.getMessage(),
                            "skipped", false
                        ))
                        .blockingGet();

                    testResults.put("accessibility", accessibilityResult);

                    if ("success".equals(accessibilityResult.get("status"))) {
                        int violations = (int) accessibilityResult.get("totalViolations");
                        log.info("  ✓ Accessibility test completed: {} violations", violations);
                        anyTestCompleted = true;
                    } else {
                        log.warn("  ✗ Accessibility test failed: {}", accessibilityResult.get("message"));
                    }
                } catch (Exception e) {
                    log.error("Accessibility test exception (continuing to next test)", e);
                    testResults.put("accessibility", Map.of(
                        "status", "error",
                        "message", "Accessibility test error: " + e.getMessage(),
                        "skipped", false
                    ));
                }
                log.info("  → Moving to next test...");
            }

            // Run Performance Tests (independent of accessibility test result)
            if (request.tests != null && request.tests.contains("performance")) {
                log.info("  → Running performance tests...");
                try {
                    // Use timeout to prevent blocking
                    ImmutableMap<String, Object> performanceResult = performanceTester
                        .runPerformanceTest(componentUrl, "")
                        .timeout(90, java.util.concurrent.TimeUnit.SECONDS)  // 90 second timeout
                        .onErrorReturn(error -> ImmutableMap.of(
                            "status", "error",
                            "message", "Performance test timed out or failed: " + error.getMessage(),
                            "skipped", false
                        ))
                        .blockingGet();

                    testResults.put("performance", performanceResult);

                    if ("success".equals(performanceResult.get("status"))) {
                        int score = (int) performanceResult.get("performanceScore");
                        log.info("  ✓ Performance test completed: score={}/100", score);
                        anyTestCompleted = true;
                    } else {
                        log.warn("  ✗ Performance test failed: {}", performanceResult.get("message"));
                    }
                } catch (Exception e) {
                    log.error("Performance test exception", e);
                    testResults.put("performance", Map.of(
                        "status", "error",
                        "message", "Performance test error: " + e.getMessage(),
                        "skipped", false
                    ));
                }
            }

            // Responsiveness tests (placeholder for future implementation)
            if (request.tests != null && request.tests.contains("responsiveness")) {
                testResults.put("responsiveness", Map.of(
                    "status", "pending",
                    "message", "Responsiveness testing will be implemented with screenshot + Vector DB"
                ));
            }

            result.put("testResults", testResults);
            result.put("status", "success");

            // Determine overall message based on test results
            String message;
            if (testResults.isEmpty()) {
                message = "Component harness deployed and server started successfully (no tests requested)";
            } else if (anyTestCompleted) {
                long errorCount = testResults.values().stream()
                    .filter(v -> v instanceof Map && "error".equals(((Map<?, ?>) v).get("status")))
                    .count();
                if (errorCount > 0) {
                    message = String.format("Workflow completed with %d test(s) having errors or timeouts", errorCount);
                } else {
                    message = "Component harness deployed, server started, and all tests completed successfully";
                }
            } else {
                message = "Component harness deployed and server started, but all tests failed or timed out";
            }

            result.put("message", message);
            result.put("testsCompleted", anyTestCompleted);

            log.info("✓ Workflow completed: {}", message);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Workflow failed", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Test and automatically fix component issues.
     * Runs tests, applies fixes, and re-tests iteratively.
     */
    @PostMapping("/test-and-fix")
    public ResponseEntity<Map<String, Object>> testAndFixComponent(@RequestBody TestAndFixRequest request) {
        log.info("Starting test-and-fix workflow for: {}", request.componentClassName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("componentClassName", request.componentClassName);
        result.put("timestamp", System.currentTimeMillis());
        result.put("maxIterations", request.maxIterations != null ? request.maxIterations : 3);

        try {
            int maxIterations = request.maxIterations != null ? request.maxIterations : 3;
            List<Map<String, Object>> iterations = new ArrayList<>();

            // First, run the initial test workflow to get the component up and running
            log.info("Running initial test workflow...");
            TestComponentRequest testRequest = new TestComponentRequest(
                request.repoPath,
                request.componentClassName,
                request.componentInputs,
                request.mockData,
                request.serviceMocks,
                request.tests != null ? request.tests : List.of("accessibility", "performance"),
                null,
                request.port
            );

            ResponseEntity<Map<String, Object>> initialTest = testComponent(testRequest);
            Map<String, Object> initialResult = initialTest.getBody();

            if (!"success".equals(initialResult.get("status"))) {
                result.put("status", "error");
                result.put("message", "Initial test workflow failed");
                result.put("initialTestResult", initialResult);
                return ResponseEntity.badRequest().body(result);
            }

            result.put("initialTestResult", initialResult);
            String componentUrl = (String) ((Map<String, Object>) initialResult.get("testResults")).get("componentUrl");

            // Iterative fix loop
            for (int i = 0; i < maxIterations; i++) {
                log.info("=== Fix Iteration {}/{} ===", i + 1, maxIterations);

                Map<String, Object> iteration = new LinkedHashMap<>();
                iteration.put("iteration", i + 1);
                iteration.put("timestamp", System.currentTimeMillis());

                // Re-run tests to get current state
                Map<String, Object> testResults = runTests(
                    request.repoPath,
                    request.componentClassName,
                    componentUrl != null ? componentUrl : "http://localhost:4200/agent-ivy-harness",
                    request.tests != null ? request.tests : List.of("accessibility", "performance")
                );

                iteration.put("testResults", testResults);

                // Check if all tests passed
                boolean allPassed = checkIfAllTestsPassed(testResults);
                if (allPassed) {
                    log.info("✓ All tests passed! Workflow complete.");
                    iteration.put("status", "passed");
                    iteration.put("message", "All tests passed - no fixes needed");
                    iterations.add(iteration);
                    result.put("status", "success");
                    result.put("message", String.format("All tests passed after %d iteration(s)", i + 1));
                    result.put("iterations", iterations);
                    return ResponseEntity.ok(result);
                }

                // Apply fixes
                List<Map<String, Object>> appliedFixes = new ArrayList<>();

                // Fix accessibility issues
                if (request.tests.contains("accessibility")) {
                    Map<String, Object> accessibilityResult = (Map<String, Object>) testResults.get("accessibility");
                    if ("success".equals(accessibilityResult.get("status"))) {
                        int violations = (int) accessibilityResult.getOrDefault("totalViolations", 0);
                        if (violations > 0) {
                            log.info("Fixing {} accessibility violation(s)...", violations);
                            ImmutableMap<String, Object> fixResult = accessibilityFixer
                                .fixAccessibilityViolations(
                                    request.repoPath,
                                    request.componentClassName,
                                    new com.google.gson.Gson().toJson(accessibilityResult.get("criticalIssues"))
                                )
                                .blockingGet();
                            appliedFixes.add(Map.of("type", "accessibility", "result", fixResult));
                        }
                    }
                }

                // Fix performance issues
                if (request.tests.contains("performance")) {
                    Map<String, Object> performanceResult = (Map<String, Object>) testResults.get("performance");
                    if ("success".equals(performanceResult.get("status"))) {
                        List<String> warnings = (List<String>) performanceResult.getOrDefault("warnings", List.of());
                        if (!warnings.isEmpty()) {
                            log.info("Fixing {} performance warning(s)...", warnings.size());
                            ImmutableMap<String, Object> fixResult = performanceFixer
                                .fixPerformanceIssues(
                                    request.repoPath,
                                    request.componentClassName,
                                    new com.google.gson.Gson().toJson(warnings),
                                    new com.google.gson.Gson().toJson(performanceResult.get("metrics"))
                                )
                                .blockingGet();
                            appliedFixes.add(Map.of("type", "performance", "result", fixResult));
                        }
                    }
                }

                iteration.put("appliedFixes", appliedFixes);

                if (appliedFixes.isEmpty()) {
                    log.info("No fixes applied - tests may have errors or already optimal");
                    iteration.put("status", "no_fixes");
                    iteration.put("message", "No fixes could be applied");
                    iterations.add(iteration);
                    break;
                }

                log.info("✓ Applied {} fix(es)", appliedFixes.size());
                iteration.put("status", "fixes_applied");
                iterations.add(iteration);

                // Rebuild and restart server for next iteration
                log.info("Restarting server to test fixes...");
                devServer.stopServer(request.repoPath).blockingGet();
                Thread.sleep(2000); // Wait for server to fully stop

                int port = request.port != null ? request.port : 4200;
                ImmutableMap<String, Object> serverResult = devServer
                    .prepareAndStartServer(request.repoPath, port)
                    .blockingGet();

                if ("error".equals(serverResult.get("status"))) {
                    log.error("Server restart failed after applying fixes");
                    iteration.put("serverError", serverResult.get("reason"));
                    break;
                }
            }

            result.put("iterations", iterations);
            result.put("status", "completed");
            result.put("message", String.format("Completed %d iteration(s) - see details in iterations array", iterations.size()));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Test-and-fix workflow failed", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Run tests and return results.
     */
    private Map<String, Object> runTests(String repoPath, String componentClassName, String componentUrl, List<String> tests) {
        Map<String, Object> testResults = new HashMap<>();

        // Run accessibility tests
        if (tests.contains("accessibility")) {
            try {
                ImmutableMap<String, Object> accessibilityResult = accessibilityTester
                    .runAccessibilityTest(componentUrl, "AA")
                    .timeout(90, java.util.concurrent.TimeUnit.SECONDS)
                    .onErrorReturn(error -> ImmutableMap.of(
                        "status", "error",
                        "message", "Accessibility test failed: " + error.getMessage()
                    ))
                    .blockingGet();
                testResults.put("accessibility", accessibilityResult);
            } catch (Exception e) {
                testResults.put("accessibility", Map.of("status", "error", "message", e.getMessage()));
            }
        }

        // Run performance tests
        if (tests.contains("performance")) {
            try {
                ImmutableMap<String, Object> performanceResult = performanceTester
                    .runPerformanceTest(componentUrl, "")
                    .timeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .onErrorReturn(error -> ImmutableMap.of(
                        "status", "error",
                        "message", "Performance test failed: " + error.getMessage()
                    ))
                    .blockingGet();
                testResults.put("performance", performanceResult);
            } catch (Exception e) {
                testResults.put("performance", Map.of("status", "error", "message", e.getMessage()));
            }
        }

        return testResults;
    }

    /**
     * Check if all tests passed.
     */
    private boolean checkIfAllTestsPassed(Map<String, Object> testResults) {
        for (Map.Entry<String, Object> entry : testResults.entrySet()) {
            Map<String, Object> result = (Map<String, Object>) entry.getValue();

            if (!"success".equals(result.get("status"))) {
                return false;
            }

            // Check accessibility violations
            if (entry.getKey().equals("accessibility")) {
                int violations = (int) result.getOrDefault("totalViolations", 0);
                if (violations > 0) {
                    return false;
                }
            }

            // Check performance warnings
            if (entry.getKey().equals("performance")) {
                List<String> warnings = (List<String>) result.getOrDefault("warnings", List.of());
                if (!warnings.isEmpty()) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * SSE endpoint for suggesting fixes without applying them.
     * Tests components and generates AI-powered fix suggestions that are streamed in real-time.
     *
     * Query Parameters:
     * - repoPath: Path to the repository (required)
     * - componentClassName: Specific component to test, or empty to test all components (optional)
     * - tests: Comma-separated test types (e.g., "accessibility,performance") (optional, default: "accessibility,performance")
     */
    @GetMapping(value = "/suggest-fixes", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter suggestFixes(
        @RequestParam String repoPath,
        @RequestParam(required = false) String componentClassName,
        @RequestParam(required = false) String tsPath,
        @RequestParam(required = false) String htmlPath,
        @RequestParam(required = false, defaultValue = "accessibility,performance") String tests
    ) {
        String sessionId = "suggest-fixes-" + System.currentTimeMillis();
        log.info("Starting SSE stream for fix suggestions: repoPath={}, component={}, sessionId={}",
            repoPath, componentClassName, sessionId);

        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 minute timeout

        // Register emitter with publisher
        sseEventPublisher.registerEmitter(sessionId, emitter);

        // Send initial "started" event
        sseEventPublisher.publishStarted(sessionId, repoPath);

        // Execute workflow asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                // Set session context for this thread
                SessionContext.setSessionId(sessionId);

                // Parse test types
                List<String> testList = tests != null && !tests.isEmpty()
                    ? Arrays.asList(tests.split(","))
                    : List.of("accessibility", "performance");

                sseEventPublisher.publishProgress(sessionId,
                    "Initializing test workflow for " + (componentClassName != null ? componentClassName : "all components"),
                    "initializing");

                // If specific component is provided, test just that one
                // Otherwise, we need to scan for all components first
                if (componentClassName != null && !componentClassName.isEmpty()) {
                    testAndSuggestFixesForComponent(sessionId, repoPath, componentClassName, testList,
                        tsPath, htmlPath, null, null);
                } else {
                    testAndSuggestFixesForAllComponents(sessionId, repoPath, testList);
                }

                // Complete the stream
                sseEventPublisher.publishCompleted(sessionId,
                    "Fix suggestions generation completed",
                    Map.of("sessionId", sessionId, "status", "completed"));

            } catch (Exception e) {
                log.error("Fix suggestion workflow failed", e);
                sseEventPublisher.publishError(sessionId, e.getMessage(), "execution");
            } finally {
                SessionContext.clear();
            }
        });

        return emitter;
    }

    /**
     * POST endpoint for suggesting fixes with file paths in request body.
     * Better for multiple components or when you need to provide file paths.
     *
     * POST /api/workflow/suggest-fixes
     * {
     *   "repoPath": "C:/Temp/agentivy/repo-123",
     *   "repoId": "repo-123456",
     *   "component": [
     *     {
     *       "name": "SettingsComponent",
     *       "tsPath": "src/app/admin/settings/settings.component.ts",
     *       "htmlPath": "src/app/admin/settings/settings.component.html",
     *       "stylesPath": "src/app/admin/settings/settings.component.scss",
     *       "type": "accessibility",
     *       "relativePath": "src/app/admin/settings"
     *     }
     *   ]
     * }
     */
    @PostMapping(value = "/suggest-fixes", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter suggestFixesPost(@RequestBody SuggestFixesRequest request) {
        String sessionId = "suggest-fixes-" + System.currentTimeMillis();
        log.info("Starting SSE stream for fix suggestions (POST): repoPath={}, repoId={}, components={}, sessionId={}",
            request.repoPath(), request.repoId(),
            request.component() != null ? request.component().size() : 0, sessionId);

        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 minute timeout

        // Register emitter with publisher
        sseEventPublisher.registerEmitter(sessionId, emitter);

        // Send initial "started" event
        sseEventPublisher.publishStarted(sessionId, request.repoPath());

        // Execute workflow asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                // Set session context for this thread
                SessionContext.setSessionId(sessionId);

                sseEventPublisher.publishProgress(sessionId,
                    "Initializing test workflow for " +
                    (request.component() != null && !request.component().isEmpty()
                        ? request.component().size() + " component(s)"
                        : "all components"),
                    "initializing");

                // Get tests array, default to ["accessibility"] if not provided
                List<String> testList = request.tests() != null && !request.tests().isEmpty()
                    ? request.tests()
                    : List.of("accessibility");

                // Process components from request body
                if (request.component() != null && !request.component().isEmpty()) {
                    for (SuggestFixesRequest.ComponentInfo comp : request.component()) {
                        testAndSuggestFixesForComponent(
                            sessionId,
                            request.repoPath(),
                            comp.name(),
                            testList,
                            comp.tsPath(),
                            comp.htmlPath(),
                            comp.stylesPath(),
                            comp.relativePath()
                        );
                    }
                } else {
                    testAndSuggestFixesForAllComponents(sessionId, request.repoPath(), testList);
                }

                // Complete the stream
                sseEventPublisher.publishCompleted(sessionId,
                    "Fix suggestions generation completed",
                    Map.of("sessionId", sessionId, "status", "completed"));

            } catch (Exception e) {
                log.error("Fix suggestion workflow failed", e);
                sseEventPublisher.publishError(sessionId, e.getMessage(), "execution");
            } finally {
                SessionContext.clear();
            }
        });

        return emitter;
    }

    /**
     * Test a specific component and generate fix suggestions.
     */
    private void testAndSuggestFixesForComponent(String sessionId, String repoPath,
                                                  String componentClassName, List<String> testList,
                                                  String providedTsPath, String providedHtmlPath,
                                                  String providedStylesPath, String providedRelativePath) {
        try {
            // Set current component in session context
            SessionContext.setCurrentComponent(componentClassName);

            sseEventPublisher.publishProgress(sessionId,
                "Extracting metadata for " + componentClassName,
                "metadata_extraction");

            // Step 1: Extract component metadata
            ImmutableMap<String, Object> metadataResult = metadataExtractor
                .extractComponentMetadata(repoPath, componentClassName)
                .blockingGet();

            if ("error".equals(metadataResult.get("status"))) {
                sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                    "metadata", "failed",
                    "Failed to extract metadata: " + metadataResult.get("message"),
                    Map.of());
                return;
            }

            String componentSelector = (String) metadataResult.get("selector");
            String importPath = (String) metadataResult.get("importPath");

            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "metadata", "completed",
                "Metadata extracted successfully",
                Map.of("selector", componentSelector, "importPath", importPath));

            // Step 2: Generate harness code
            sseEventPublisher.publishProgress(sessionId,
                "Generating test harness for " + componentClassName,
                "harness_generation");

            ImmutableMap<String, Object> codeResult = codeGenerator
                .generateHarnessCode(componentClassName, componentSelector, importPath, "", "", "", "")
                .blockingGet();

            if ("error".equals(codeResult.get("status"))) {
                sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                    "harness", "failed",
                    "Failed to generate harness: " + codeResult.get("message"),
                    Map.of());
                return;
            }

            String generatedCode = (String) codeResult.get("code");
            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "harness", "completed",
                "Harness code generated",
                Map.of("codeLength", generatedCode.length()));

            // Step 3: Deploy harness
            sseEventPublisher.publishProgress(sessionId,
                "Deploying test harness",
                "harness_deployment");

            ImmutableMap<String, Object> deployResult = deployer
                .deployHarness(repoPath, generatedCode)
                .blockingGet();

            if ("error".equals(deployResult.get("status"))) {
                sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                    "deployment", "failed",
                    "Failed to deploy harness: " + deployResult.get("message"),
                    Map.of());
                return;
            }

            // Step 4: Start dev server
            sseEventPublisher.publishProgress(sessionId,
                "Starting Angular dev server",
                "server_startup");

            ImmutableMap<String, Object> serverResult = devServer
                .prepareAndStartServer(repoPath, 4200)
                .blockingGet();

            if ("error".equals(serverResult.get("status"))) {
                sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                    "dev-server", "failed",
                    "Failed to start server: " + serverResult.get("message"),
                    Map.of());
                return;
            }

            // Get the harness URL from the server result (not componentUrl which doesn't exist)
            String harnessUrl = (String) serverResult.get("harnessUrl");
            String serverUrl = (String) serverResult.get("serverUrl");

            // Build component URL: harnessUrl + component selector query parameter
            String componentUrl = harnessUrl + "?component=" + componentSelector;

            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "dev-server", "completed",
                "Server started successfully",
                Map.of(
                    "url", componentUrl,
                    "serverUrl", serverUrl != null ? serverUrl : "",
                    "harnessUrl", harnessUrl != null ? harnessUrl : ""
                ));

            // Step 5: Run tests and generate suggestions
            for (String testType : testList) {
                generateFixSuggestions(sessionId, componentClassName, componentUrl,
                    componentSelector, testType, repoPath, metadataResult,
                    providedTsPath, providedHtmlPath, providedStylesPath, providedRelativePath);
            }

            // Step 6: Stop server
            sseEventPublisher.publishProgress(sessionId,
                "Stopping dev server",
                "cleanup");

            devServer.stopServer(repoPath).blockingGet();

            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "dev-server", "stopped",
                "Server stopped",
                Map.of());

        } catch (Exception e) {
            log.error("Failed to test component: {}", componentClassName, e);
            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "test", "failed",
                "Error during testing: " + e.getMessage(),
                Map.of());
        }
    }

    /**
     * Scan for all components and test each one.
     */
    private void testAndSuggestFixesForAllComponents(String sessionId, String repoPath, List<String> testList) {
        // TODO: Implement scanning and testing all components
        // This would require integrating with GitHubComponentScannerTool
        sseEventPublisher.publishProgress(sessionId,
            "Scanning for all components is not yet implemented. Please specify a componentClassName.",
            "error");
        sseEventPublisher.publishError(sessionId,
            "Component scanning not implemented. Please provide a specific componentClassName.",
            "initialization");
    }

    /**
     * Generate fix suggestions for a specific test type.
     */
    private void generateFixSuggestions(String sessionId, String componentName, String componentUrl,
                                         String componentSelector, String testType, String repoPath,
                                         ImmutableMap<String, Object> metadata, String providedTsPath,
                                         String providedHtmlPath, String providedStylesPath, String providedRelativePath) {
        try {
            sseEventPublisher.publishComponentStatus(sessionId, componentName,
                testType, "running",
                "Running " + testType + " test",
                Map.of());

            if (testType.equals("accessibility")) {
                // Run accessibility test
                ImmutableMap<String, Object> testResult = accessibilityTester
                    .runAccessibilityTest(componentUrl, "AA")
                    .blockingGet();

                if ("success".equals(testResult.get("status"))) {
                    // AccessibilityTestingTool returns "allViolations" directly, not wrapped in "results"
                    List<Map<String, Object>> violations = (List<Map<String, Object>>) testResult.get("allViolations");

                    // Handle null violations list
                    if (violations == null) {
                        violations = List.of();
                    }

                    sseEventPublisher.publishComponentStatus(sessionId, componentName,
                        testType, "completed",
                        violations.size() + " accessibility violations found",
                        Map.of("violationCount", violations.size()));

                    // Generate suggestions for each violation
                    if (!violations.isEmpty()) {
                        // Publish in-progress event to let frontend know we're generating fixes
                        sseEventPublisher.publishComponentStatus(sessionId, componentName,
                            "accessibility-fix", "in-progress",
                            "Generating AI-powered fix suggestions for " + violations.size() + " violation(s)...",
                            Map.of("violationCount", violations.size()));

                        // Use the fixer tool to generate suggestions (but don't apply them)
                        // Priority: 1) metadata paths, 2) provided paths from API, 3) null
                        String tsPath = (String) metadata.get("tsPath");
                        String htmlPath = (String) metadata.get("htmlPath");
                        String stylesPath = (String) metadata.get("stylesPath");

                        // Fall back to provided paths if metadata doesn't have them
                        if (tsPath == null && providedTsPath != null) {
                            tsPath = providedTsPath;
                            log.info("Using provided tsPath for {}: {}", componentName, tsPath);
                        }
                        if (htmlPath == null && providedHtmlPath != null) {
                            htmlPath = providedHtmlPath;
                            log.info("Using provided htmlPath for {}: {}", componentName, htmlPath);
                        }
                        if (stylesPath == null && providedStylesPath != null) {
                            stylesPath = providedStylesPath;
                            log.info("Using provided stylesPath for {}: {}", componentName, stylesPath);
                        }

                        // Log the file paths to help diagnose missing source code issues
                        log.info("File paths for {}: tsPath={}, htmlPath={}, stylesPath={}", componentName, tsPath, htmlPath, stylesPath);
                        if (tsPath == null || htmlPath == null) {
                            log.warn("Missing file paths for {}. AI will generate generic fixes without seeing actual source code.", componentName);
                        }

                        ImmutableMap<String, Object> fixSuggestionResult = accessibilityFixer
                            .suggestFixes(repoPath, componentName, tsPath, htmlPath, stylesPath, violations)
                            .blockingGet();

                        if ("success".equals(fixSuggestionResult.get("status"))) {
                            String suggestedFix = (String) fixSuggestionResult.get("suggestedFix");
                            String explanation = (String) fixSuggestionResult.get("explanation");

                            // Publish fix suggestion event
                            sseEventPublisher.publishFixSuggestion(
                                sessionId,
                                componentName,
                                "accessibility",
                                Map.of("violations", violations, "violationCount", violations.size()),
                                suggestedFix,
                                explanation,
                                htmlPath,
                                violations.size() > 5 ? 3 : violations.size() > 2 ? 2 : 1 // severity based on count
                            );

                            // Publish completed status
                            sseEventPublisher.publishComponentStatus(sessionId, componentName,
                                "accessibility-fix", "completed",
                                "Fix suggestions generated successfully",
                                Map.of("fixGenerated", true));
                        } else {
                            // Publish failed status if fix generation failed
                            sseEventPublisher.publishComponentStatus(sessionId, componentName,
                                "accessibility-fix", "failed",
                                "Failed to generate fix suggestions: " + fixSuggestionResult.get("message"),
                                Map.of());
                        }
                    }
                }
            } else if (testType.equals("performance")) {
                // Run performance test
                ImmutableMap<String, Object> testResult = performanceTester
                    .runPerformanceTest(componentUrl, componentSelector)
                    .timeout(90, java.util.concurrent.TimeUnit.SECONDS)
                    .blockingGet();

                if ("success".equals(testResult.get("status"))) {
                    Map<String, Object> metrics = (Map<String, Object>) testResult.get("metrics");
                    List<String> warnings = (List<String>) testResult.get("warnings");

                    // Handle null cases
                    if (metrics == null) {
                        metrics = Map.of();
                    }
                    if (warnings == null) {
                        warnings = List.of();
                    }

                    sseEventPublisher.publishComponentStatus(sessionId, componentName,
                        testType, "completed",
                        warnings.size() + " performance warnings found",
                        Map.of("warningCount", warnings.size(), "metrics", metrics));

                    // Generate performance fix suggestions if there are warnings
                    if (!warnings.isEmpty()) {
                        // Publish in-progress event to let frontend know we're generating fixes
                        sseEventPublisher.publishComponentStatus(sessionId, componentName,
                            "performance-fix", "in-progress",
                            "Generating AI-powered fix suggestions for " + warnings.size() + " warning(s)...",
                            Map.of("warningCount", warnings.size()));

                        String tsPath = (String) metadata.get("tsPath");

                        // Fall back to provided tsPath if metadata doesn't have it
                        if (tsPath == null && providedTsPath != null) {
                            tsPath = providedTsPath;
                            log.info("Using provided tsPath for performance fixes in {}: {}", componentName, tsPath);
                        }

                        log.info("TypeScript path for performance fixes in {}: {}", componentName, tsPath);
                        if (tsPath == null) {
                            log.warn("Missing TypeScript path for {}. AI will generate generic performance fixes.", componentName);
                        }

                        ImmutableMap<String, Object> fixSuggestionResult = performanceFixer
                            .suggestFixes(repoPath, componentName, tsPath, warnings, metrics)
                            .blockingGet();

                        if ("success".equals(fixSuggestionResult.get("status"))) {
                            String suggestedFix = (String) fixSuggestionResult.get("suggestedFix");
                            String explanation = (String) fixSuggestionResult.get("explanation");

                            // Publish fix suggestion event
                            sseEventPublisher.publishFixSuggestion(
                                sessionId,
                                componentName,
                                "performance",
                                Map.of("warnings", warnings, "metrics", metrics),
                                suggestedFix,
                                explanation,
                                tsPath,
                                warnings.size() > 3 ? 3 : warnings.size() > 1 ? 2 : 1 // severity based on count
                            );

                            // Publish completed status
                            sseEventPublisher.publishComponentStatus(sessionId, componentName,
                                "performance-fix", "completed",
                                "Fix suggestions generated successfully",
                                Map.of("fixGenerated", true));
                        } else {
                            // Publish failed status if fix generation failed
                            sseEventPublisher.publishComponentStatus(sessionId, componentName,
                                "performance-fix", "failed",
                                "Failed to generate fix suggestions: " + fixSuggestionResult.get("message"),
                                Map.of());
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to generate {} suggestions for {}", testType, componentName, e);
            sseEventPublisher.publishComponentStatus(sessionId, componentName,
                testType, "failed",
                "Error: " + e.getMessage(),
                Map.of());
        }
    }

    @PostMapping("/stop-server")
    public ResponseEntity<Map<String, Object>> stopServer(@RequestBody Map<String, String> request) {
        String repoPath = request.get("repoPath");
        log.info("Stopping server for: {}", repoPath);

        try {
            ImmutableMap<String, Object> stopResult = devServer
                .stopServer(repoPath)
                .blockingGet();

            return ResponseEntity.ok(stopResult);
        } catch (Exception e) {
            log.error("Failed to stop server", e);
            return ResponseEntity.internalServerError().body(
                Map.of("status", "error", "message", e.getMessage())
            );
        }
    }

    // Request DTOs
    public record TestComponentRequest(
        String repoPath,
        String componentClassName,
        String componentInputs,
        String mockData,
        String serviceMocks,
        List<String> tests,
        String fixStrategy,
        Integer port
    ) {}

    public record TestAndFixRequest(
        String repoPath,
        String componentClassName,
        String componentInputs,
        String mockData,
        String serviceMocks,
        List<String> tests,
        Integer maxIterations,
        Integer port
    ) {}

    /**
     * Request body for POST /suggest-fixes endpoint.
     * Supports batch processing of multiple components with their file paths.
     * Matches the format used by /api/v1/agent/repo endpoint.
     */
    public record SuggestFixesRequest(
        String repoPath,
        String repoId,
        List<ComponentInfo> component,
        List<String> tests          // ["accessibility", "performance", "unit", "e2e"]
    ) {
        /**
         * Individual component info with file paths.
         */
        public record ComponentInfo(
            String name,
            String tsPath,
            String htmlPath,
            String stylesPath,
            String relativePath
        ) {}
    }
}
