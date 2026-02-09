package com.agentivy.backend.controller;

import com.agentivy.backend.dto.WorkflowResult;
import com.agentivy.backend.util.ScoringUtils;
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

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    // Dedicated executor for long-running SSE workflows (avoids ForkJoinPool issues)
    private final ExecutorService workflowExecutor = Executors.newCachedThreadPool();

    @PreDestroy
    public void shutdown() {
        workflowExecutor.shutdown();
    }

    @PostMapping("/test-component")
    public ResponseEntity<Map<String, Object>> testComponent(@RequestBody TestComponentRequest request) {
        log.info("Starting component test workflow for: {}", request.componentClassName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("componentClassName", request.componentClassName);
        result.put("timestamp", System.currentTimeMillis());

        try {
            ImmutableMap<String, Object> metadataResult = extractMetadata(request, result);
            if (metadataResult == null) return ResponseEntity.badRequest().body(result);

            String generatedCode = generateHarness(request, metadataResult, result);
            if (generatedCode == null) return ResponseEntity.badRequest().body(result);

            ImmutableMap<String, Object> deployResult = deployHarness(request, generatedCode, result);
            if (deployResult == null) return ResponseEntity.badRequest().body(result);

            ImmutableMap<String, Object> serverResult = startDevServer(request, result);
            if (serverResult == null) return ResponseEntity.badRequest().body(result);

            String componentUrl = (String) serverResult.get("harnessUrl");
            Map<String, Object> testResults = executeTests(request, componentUrl);

            result.put("testResults", testResults);
            result.put("status", "success");
            result.put("message", buildTestSummaryMessage(testResults));
            result.put("testsCompleted", hasAnyCompletedTest(testResults));

            log.info("Workflow completed: {}", result.get("message"));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Workflow failed", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /** Step 1: Extract component metadata. Returns null on failure (result map is populated with error). */
    private ImmutableMap<String, Object> extractMetadata(TestComponentRequest request, Map<String, Object> result) {
        log.info("Step 1: Extracting component metadata...");
        ImmutableMap<String, Object> metadataResult = metadataExtractor
            .extractComponentMetadata(request.repoPath, request.componentClassName)
            .blockingGet();

        if ("error".equals(metadataResult.get("status"))) {
            result.put("status", "error");
            result.put("step", "metadata_extraction");
            result.put("error", metadataResult.get("message"));
            return null;
        }

        result.put("metadata", metadataResult);
        log.info("Metadata extracted: selector={}", metadataResult.get("selector"));
        return metadataResult;
    }

    /** Step 2: Generate harness code via LLM. Returns null on failure. */
    private String generateHarness(TestComponentRequest request, ImmutableMap<String, Object> metadataResult, Map<String, Object> result) {
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
            return null;
        }

        String generatedCode = (String) codeResult.get("code");
        result.put("harnessCodeGenerated", true);
        result.put("codeLength", generatedCode.length());
        log.info("Harness code generated ({} chars)", generatedCode.length());
        return generatedCode;
    }

    /** Step 3: Deploy the generated harness to the project. Returns null on failure. */
    private ImmutableMap<String, Object> deployHarness(TestComponentRequest request, String generatedCode, Map<String, Object> result) {
        log.info("Step 3: Deploying harness to project...");
        ImmutableMap<String, Object> deployResult = deployer
            .deployHarness(request.repoPath, generatedCode)
            .blockingGet();

        if ("error".equals(deployResult.get("status"))) {
            result.put("status", "error");
            result.put("step", "deployment");
            result.put("error", deployResult.get("message"));
            return null;
        }

        result.put("harnessDeployed", true);
        result.put("harnessFilePath", deployResult.get("harnessFilePath"));
        result.put("harnessUrl", deployResult.get("harnessUrl"));
        log.info("Harness deployed to: {}", deployResult.get("harnessFilePath"));
        return deployResult;
    }

    /** Step 4: Start the Angular dev server. Returns null on failure. */
    private ImmutableMap<String, Object> startDevServer(TestComponentRequest request, Map<String, Object> result) {
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
            return null;
        }

        result.put("serverStarted", true);
        result.put("serverUrl", serverResult.get("serverUrl"));
        result.put("componentUrl", serverResult.get("harnessUrl"));
        log.info("Server started at: {}", serverResult.get("serverUrl"));
        return serverResult;
    }

    /** Step 5: Run requested tests against the component URL. */
    private Map<String, Object> executeTests(TestComponentRequest request, String componentUrl) {
        log.info("Step 5: Running tests...");
        Map<String, Object> testResults = new HashMap<>();

        if (request.tests != null && request.tests.contains("accessibility")) {
            testResults.put("accessibility", runAccessibilityTest(componentUrl));
        }
        if (request.tests != null && request.tests.contains("performance")) {
            testResults.put("performance", runPerformanceTest(componentUrl));
        }
        if (request.tests != null && request.tests.contains("responsiveness")) {
            testResults.put("responsiveness", Map.of(
                "status", "pending",
                "message", "Responsiveness testing will be implemented with screenshot + Vector DB"
            ));
        }

        return testResults;
    }

    /** Run a single accessibility test with timeout protection. */
    private Map<String, Object> runAccessibilityTest(String componentUrl) {
        log.info("  Running accessibility tests...");
        try {
            ImmutableMap<String, Object> accessibilityResult = accessibilityTester
                .runAccessibilityTest(componentUrl, "AA")
                .timeout(90, java.util.concurrent.TimeUnit.SECONDS)
                .onErrorReturn(error -> ImmutableMap.of(
                    "status", "error",
                    "message", "Accessibility test timed out or failed: " + error.getMessage(),
                    "skipped", false
                ))
                .blockingGet();

            if ("success".equals(accessibilityResult.get("status"))) {
                log.info("  Accessibility test completed: {} violations", accessibilityResult.get("totalViolations"));
            } else {
                log.warn("  Accessibility test failed: {}", accessibilityResult.get("message"));
            }
            return accessibilityResult;
        } catch (Exception e) {
            log.error("Accessibility test exception", e);
            return Map.of("status", "error", "message", "Accessibility test error: " + e.getMessage(), "skipped", false);
        }
    }

    /** Run a single performance test with timeout protection. */
    private Map<String, Object> runPerformanceTest(String componentUrl) {
        log.info("  Running performance tests...");
        try {
            ImmutableMap<String, Object> performanceResult = performanceTester
                .runPerformanceTest(componentUrl, "")
                .timeout(90, java.util.concurrent.TimeUnit.SECONDS)
                .onErrorReturn(error -> ImmutableMap.of(
                    "status", "error",
                    "message", "Performance test timed out or failed: " + error.getMessage(),
                    "skipped", false
                ))
                .blockingGet();

            if ("success".equals(performanceResult.get("status"))) {
                log.info("  Performance test completed: score={}/100", performanceResult.get("performanceScore"));
            } else {
                log.warn("  Performance test failed: {}", performanceResult.get("message"));
            }
            return performanceResult;
        } catch (Exception e) {
            log.error("Performance test exception", e);
            return Map.of("status", "error", "message", "Performance test error: " + e.getMessage(), "skipped", false);
        }
    }

    private boolean hasAnyCompletedTest(Map<String, Object> testResults) {
        return testResults.values().stream()
            .anyMatch(v -> v instanceof Map && "success".equals(((Map<?, ?>) v).get("status")));
    }

    private String buildTestSummaryMessage(Map<String, Object> testResults) {
        if (testResults.isEmpty()) {
            return "Component harness deployed and server started successfully (no tests requested)";
        }

        boolean anyCompleted = hasAnyCompletedTest(testResults);
        long errorCount = testResults.values().stream()
            .filter(v -> v instanceof Map && "error".equals(((Map<?, ?>) v).get("status")))
            .count();

        if (anyCompleted && errorCount > 0) {
            return String.format("Workflow completed with %d test(s) having errors or timeouts", errorCount);
        } else if (anyCompleted) {
            return "Component harness deployed, server started, and all tests completed successfully";
        } else {
            return "Component harness deployed and server started, but all tests failed or timed out";
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

            // Check the "passed" field set by each test tool via ScoringUtils
            Object passed = result.get("passed");
            if (passed instanceof Boolean && !(Boolean) passed) {
                return false;
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

        // Execute workflow asynchronously using dedicated executor
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
                    try {
                        testAndSuggestFixesForComponent(sessionId, repoPath, componentClassName, testList,
                            tsPath, htmlPath, null, null);
                    } catch (Exception compEx) {
                        log.error("Error testing component {}: {}", componentClassName, compEx.getMessage(), compEx);
                        sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                            "test", "failed",
                            "Error: " + compEx.getMessage(),
                            Map.of("error", compEx.getMessage()));
                    }
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
        }, workflowExecutor);

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
        int componentCount = request.component() != null ? request.component().size() : 0;
        log.info("Starting SSE stream for fix suggestions (POST): repoPath={}, repoId={}, components={}, sessionId={}",
            request.repoPath(), request.repoId(), componentCount, sessionId);

        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 minute timeout

        // Register emitter with publisher
        sseEventPublisher.registerEmitter(sessionId, emitter);

        // Execute workflow asynchronously using dedicated executor (not ForkJoinPool)
        CompletableFuture.runAsync(() -> {
            try {
                // Set session context for this thread
                SessionContext.setSessionId(sessionId);

                // Get tests array, default to ["accessibility"] if not provided
                List<String> testList = request.tests() != null && !request.tests().isEmpty()
                    ? request.tests()
                    : List.of("accessibility");

                // Send "start" event with total components and test types
                sseEventPublisher.publishWorkflowStarted(sessionId,
                    "Starting analysis for " + componentCount + " component(s)",
                    componentCount,
                    testList);

                // Collect results for all components
                List<WorkflowResult.ComponentTestResult> componentResults = new ArrayList<>();

                // Process components from request body SEQUENTIALLY (one at a time)
                if (request.component() != null && !request.component().isEmpty()) {
                    for (int i = 0; i < request.component().size(); i++) {
                        SuggestFixesRequest.ComponentInfo comp = request.component().get(i);

                        // Emit progress event with "1/3" format
                        sseEventPublisher.publishProgress(sessionId,
                            "Testing " + comp.name(),
                            "testing",
                            i + 1,
                            request.component().size());

                        try {
                            WorkflowResult.ComponentTestResult result = testAndSuggestFixesForComponent(
                                sessionId,
                                request.repoPath(),
                                comp.name(),
                                testList,
                                comp.tsPath(),
                                comp.htmlPath(),
                                comp.stylesPath(),
                                comp.relativePath(),
                                i,
                                request.component().size()
                            );

                            if (result != null) {
                                componentResults.add(result);

                                // Emit component-result event
                                sseEventPublisher.publishWorkflowComponentResult(sessionId, result);
                            }
                        } catch (Exception compEx) {
                            // Log component-level error but continue with other components
                            log.error("Error testing component {}: {}", comp.name(), compEx.getMessage(), compEx);
                            sseEventPublisher.publishComponentStatus(sessionId, comp.name(),
                                "test", "failed",
                                "Error: " + compEx.getMessage(),
                                Map.of("error", compEx.getMessage()));
                        }
                    }
                } else {
                    testAndSuggestFixesForAllComponents(sessionId, request.repoPath(), testList);
                }

                // Build and emit final summary
                WorkflowResult.WorkflowSummary summary = buildWorkflowSummary(
                    request.repoId(), componentResults, testList);
                sseEventPublisher.publishWorkflowSummary(sessionId, summary);

                // Emit done event and complete emitters
                sseEventPublisher.publishWorkflowDone(sessionId, "Analysis complete");

            } catch (Exception e) {
                log.error("Fix suggestion workflow failed", e);
                sseEventPublisher.publishError(sessionId, e.getMessage(), "execution");
            } finally {
                SessionContext.clear();
            }
        }, workflowExecutor);

        return emitter;
    }

    /**
     * Test a specific component and generate fix suggestions.
     * Returns a ComponentTestResult with scored results for all test types.
     */
    private WorkflowResult.ComponentTestResult testAndSuggestFixesForComponent(
            String sessionId, String repoPath,
            String componentClassName, List<String> testList,
            String providedTsPath, String providedHtmlPath,
            String providedStylesPath, String providedRelativePath,
            int componentIndex, int totalComponents) {
        try {
            // Set current component in session context
            SessionContext.setCurrentComponent(componentClassName);

            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "metadata", "starting",
                "Extracting metadata for " + componentClassName,
                Map.of());

            // Step 1: Extract component metadata
            ImmutableMap<String, Object> metadataResult = metadataExtractor
                .extractComponentMetadata(repoPath, componentClassName)
                .blockingGet();

            if ("error".equals(metadataResult.get("status"))) {
                sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                    "metadata", "failed",
                    "Failed to extract metadata: " + metadataResult.get("message"),
                    Map.of());
                return null;
            }

            String componentSelector = (String) metadataResult.get("selector");
            String importPath = (String) metadataResult.get("importPath");

            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "metadata", "completed",
                "Metadata extracted successfully",
                Map.of("selector", componentSelector, "importPath", importPath));

            // Step 2: Generate harness code
            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "harness", "starting",
                "Generating test harness for " + componentClassName,
                Map.of());

            ImmutableMap<String, Object> codeResult = codeGenerator
                .generateHarnessCode(componentClassName, componentSelector, importPath, "", "", "", "")
                .blockingGet();

            if ("error".equals(codeResult.get("status"))) {
                sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                    "harness", "failed",
                    "Failed to generate harness: " + codeResult.get("message"),
                    Map.of());
                return null;
            }

            String generatedCode = (String) codeResult.get("code");
            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "harness", "completed",
                "Harness code generated",
                Map.of("codeLength", generatedCode.length()));

            // Step 3: Deploy harness
            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "deployment", "starting",
                "Deploying test harness",
                Map.of());

            ImmutableMap<String, Object> deployResult = deployer
                .deployHarness(repoPath, generatedCode)
                .blockingGet();

            if ("error".equals(deployResult.get("status"))) {
                sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                    "deployment", "failed",
                    "Failed to deploy harness: " + deployResult.get("message"),
                    Map.of());
                return null;
            }

            // Step 4: Start dev server
            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "dev-server", "starting",
                "Starting Angular dev server",
                Map.of());

            ImmutableMap<String, Object> serverResult = devServer
                .prepareAndStartServer(repoPath, 4200)
                .blockingGet();

            if ("error".equals(serverResult.get("status"))) {
                sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                    "dev-server", "failed",
                    "Failed to start server: " + serverResult.get("message"),
                    Map.of());
                return null;
            }

            // Get the harness URL from the server result
            String harnessUrl = (String) serverResult.get("harnessUrl");
            String serverUrl = (String) serverResult.get("serverUrl");
            String componentUrl = harnessUrl + "?component=" + componentSelector;

            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "dev-server", "completed",
                "Server started successfully",
                Map.of(
                    "url", componentUrl,
                    "serverUrl", serverUrl != null ? serverUrl : "",
                    "harnessUrl", harnessUrl != null ? harnessUrl : ""
                ));

            // Step 5: Run tests and generate suggestions, collecting TestResults
            WorkflowResult.TestResult accessibilityResult = null;
            WorkflowResult.TestResult performanceResult = null;

            for (String testType : testList) {
                WorkflowResult.TestResult testResult = generateFixSuggestions(
                    sessionId, componentClassName, componentUrl,
                    componentSelector, testType, repoPath, metadataResult,
                    providedTsPath, providedHtmlPath, providedStylesPath, providedRelativePath);

                if (testResult != null) {
                    if ("accessibility".equals(testType)) {
                        accessibilityResult = testResult;
                    } else if ("performance".equals(testType)) {
                        performanceResult = testResult;
                    }
                }
            }

            // Step 6: Stop server
            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "dev-server", "stopping",
                "Stopping dev server",
                Map.of());

            devServer.stopServer(repoPath).blockingGet();

            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "dev-server", "stopped",
                "Server stopped",
                Map.of());

            // Build ComponentInfo
            String fullName = providedRelativePath != null
                ? providedRelativePath + "/" + componentClassName
                : componentClassName;

            WorkflowResult.ComponentInfo compInfo = new WorkflowResult.ComponentInfo(
                componentClassName,
                providedRelativePath,
                fullName,
                providedTsPath,
                providedHtmlPath,
                providedStylesPath
            );

            return new WorkflowResult.ComponentTestResult(compInfo, accessibilityResult, performanceResult);

        } catch (Exception e) {
            log.error("Failed to test component: {}", componentClassName, e);
            sseEventPublisher.publishComponentStatus(sessionId, componentClassName,
                "test", "failed",
                "Error during testing: " + e.getMessage(),
                Map.of());
            return null;
        }
    }

    /**
     * Overloaded version for the GET endpoint (backward compatibility).
     */
    private void testAndSuggestFixesForComponent(String sessionId, String repoPath,
                                                  String componentClassName, List<String> testList,
                                                  String providedTsPath, String providedHtmlPath,
                                                  String providedStylesPath, String providedRelativePath) {
        testAndSuggestFixesForComponent(sessionId, repoPath, componentClassName, testList,
            providedTsPath, providedHtmlPath, providedStylesPath, providedRelativePath, 0, 1);
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
     * Returns a WorkflowResult.TestResult with scored results and grouped-by-file details.
     */
    private WorkflowResult.TestResult generateFixSuggestions(String sessionId, String componentName, String componentUrl,
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

                // Handle error status from accessibility test
                if ("error".equals(testResult.get("status"))) {
                    String errorMsg = (String) testResult.getOrDefault("message", "Unknown error");
                    log.error("Accessibility test failed for {}: {}", componentName, errorMsg);
                    sseEventPublisher.publishComponentStatus(sessionId, componentName,
                        testType, "failed",
                        "Accessibility test failed: " + errorMsg,
                        Map.of("error", errorMsg));
                    return null;
                }

                if ("success".equals(testResult.get("status"))) {
                    List<Map<String, Object>> violations = (List<Map<String, Object>>) testResult.get("allViolations");
                    if (violations == null) {
                        violations = List.of();
                    }

                    // Get severity counts from the test result
                    Map<String, Integer> severityCounts = (Map<String, Integer>) testResult.get("severityCounts");
                    int critical = severityCounts != null ? severityCounts.getOrDefault("critical", 0) : 0;
                    int serious = severityCounts != null ? severityCounts.getOrDefault("serious", 0) : 0;
                    int moderate = severityCounts != null ? severityCounts.getOrDefault("moderate", 0) : 0;
                    int minor = severityCounts != null ? severityCounts.getOrDefault("minor", 0) : 0;

                    // Calculate weighted score
                    int score = ScoringUtils.calculateWeightedScore(critical, serious, moderate, minor);
                    String status = ScoringUtils.statusFromScore(score);

                    sseEventPublisher.publishComponentStatus(sessionId, componentName,
                        testType, "completed",
                        violations.size() + " accessibility violations found (score: " + score + ", status: " + status + ")",
                        Map.of("violationCount", violations.size(), "score", score, "status", status));

                    // Build violation counts
                    WorkflowResult.ViolationCounts violationCounts = new WorkflowResult.ViolationCounts(
                        critical, serious, moderate, minor, violations.size());

                    // Grouped-by-file details
                    List<WorkflowResult.FileIssues> details;

                    // Generate AI suggestions if there are violations
                    if (!violations.isEmpty()) {
                        sseEventPublisher.publishComponentStatus(sessionId, componentName,
                            "accessibility-fix", "in-progress",
                            "Generating AI-powered fix suggestions for " + violations.size() + " violation(s)...",
                            Map.of("violationCount", violations.size()));

                        // Resolve file paths: priority metadata > provided
                        String tsPath = (String) metadata.get("tsPath");
                        String htmlPath = (String) metadata.get("htmlPath");
                        String stylesPath = (String) metadata.get("stylesPath");

                        if (tsPath == null && providedTsPath != null) tsPath = providedTsPath;
                        if (htmlPath == null && providedHtmlPath != null) htmlPath = providedHtmlPath;
                        if (stylesPath == null && providedStylesPath != null) stylesPath = providedStylesPath;

                        log.info("File paths for {}: tsPath={}, htmlPath={}, stylesPath={}", componentName, tsPath, htmlPath, stylesPath);

                        ImmutableMap<String, Object> fixSuggestionResult = accessibilityFixer
                            .suggestFixes(repoPath, componentName, tsPath, htmlPath, stylesPath, violations)
                            .blockingGet();

                        // Build grouped-by-file details with AI suggestions
                        if ("success".equals(fixSuggestionResult.get("status"))) {
                            String suggestedFix = (String) fixSuggestionResult.get("suggestedFix");
                            String explanation = (String) fixSuggestionResult.get("explanation");

                            details = groupViolationsByFile(violations, htmlPath, tsPath, stylesPath, explanation);

                            sseEventPublisher.publishComponentStatus(sessionId, componentName,
                                "accessibility-fix", "completed",
                                "Fix suggestions generated successfully",
                                Map.of("fixGenerated", true, "score", score));
                        } else {
                            details = groupViolationsByFile(violations, htmlPath, tsPath, stylesPath, null);
                            sseEventPublisher.publishComponentStatus(sessionId, componentName,
                                "accessibility-fix", "failed",
                                "Failed to generate fix suggestions: " + fixSuggestionResult.get("message"),
                                Map.of());
                        }
                    } else {
                        details = List.of();
                    }

                    return new WorkflowResult.TestResult(
                        status, score, violationCounts,
                        WorkflowResult.PassThreshold.defaultThreshold(),
                        details);
                }
            } else if (testType.equals("performance")) {
                // Run performance test
                ImmutableMap<String, Object> testResult = performanceTester
                    .runPerformanceTest(componentUrl, componentSelector)
                    .timeout(90, java.util.concurrent.TimeUnit.SECONDS)
                    .blockingGet();

                // Handle error status from performance test
                if ("error".equals(testResult.get("status"))) {
                    String errorMsg = (String) testResult.getOrDefault("message", "Unknown error");
                    log.error("Performance test failed for {}: {}", componentName, errorMsg);
                    sseEventPublisher.publishComponentStatus(sessionId, componentName,
                        testType, "failed",
                        "Performance test failed: " + errorMsg,
                        Map.of("error", errorMsg));
                    return null;
                }

                if ("success".equals(testResult.get("status"))) {
                    Map<String, Object> metrics = (Map<String, Object>) testResult.get("metrics");
                    List<String> warnings = (List<String>) testResult.get("warnings");

                    if (metrics == null) metrics = Map.of();
                    if (warnings == null) warnings = List.of();

                    // Use existing performance score (already 0-100)
                    Object perfScoreObj = testResult.get("performanceScore");
                    int perfScore = perfScoreObj instanceof Number ? ((Number) perfScoreObj).intValue() : 100;
                    String status = ScoringUtils.performanceStatusFromScore(perfScore);

                    // Categorize warnings by severity for ViolationCounts
                    // Performance warnings don't have built-in severity, so estimate based on warning content
                    int perfCritical = 0, perfSerious = 0, perfModerate = 0, perfMinor = 0;
                    for (String warning : warnings) {
                        String lower = warning.toLowerCase();
                        if (lower.contains("memory leak") || lower.contains("blocking") || lower.contains("infinite")) {
                            perfCritical++;
                        } else if (lower.contains("excessive") || lower.contains("large") || lower.contains("no lazy")) {
                            perfSerious++;
                        } else if (lower.contains("missing") || lower.contains("trackby") || lower.contains("change detection")) {
                            perfModerate++;
                        } else {
                            perfMinor++;
                        }
                    }

                    WorkflowResult.ViolationCounts violationCounts = new WorkflowResult.ViolationCounts(
                        perfCritical, perfSerious, perfModerate, perfMinor, warnings.size());

                    sseEventPublisher.publishComponentStatus(sessionId, componentName,
                        testType, "completed",
                        warnings.size() + " performance warnings found (score: " + perfScore + ", status: " + status + ")",
                        Map.of("warningCount", warnings.size(), "score", perfScore, "status", status));

                    // Grouped-by-file details for performance
                    List<WorkflowResult.FileIssues> details;

                    if (!warnings.isEmpty()) {
                        sseEventPublisher.publishComponentStatus(sessionId, componentName,
                            "performance-fix", "in-progress",
                            "Generating AI-powered fix suggestions for " + warnings.size() + " warning(s)...",
                            Map.of("warningCount", warnings.size()));

                        String tsPath = (String) metadata.get("tsPath");
                        if (tsPath == null && providedTsPath != null) tsPath = providedTsPath;

                        log.info("TypeScript path for performance fixes in {}: {}", componentName, tsPath);

                        ImmutableMap<String, Object> fixSuggestionResult = performanceFixer
                            .suggestFixes(repoPath, componentName, tsPath, warnings, metrics)
                            .blockingGet();

                        if ("success".equals(fixSuggestionResult.get("status"))) {
                            String explanation = (String) fixSuggestionResult.get("explanation");
                            details = groupPerformanceWarningsByFile(warnings, tsPath, explanation);

                            sseEventPublisher.publishComponentStatus(sessionId, componentName,
                                "performance-fix", "completed",
                                "Fix suggestions generated successfully",
                                Map.of("fixGenerated", true, "score", perfScore));
                        } else {
                            details = groupPerformanceWarningsByFile(warnings, tsPath, null);
                            sseEventPublisher.publishComponentStatus(sessionId, componentName,
                                "performance-fix", "failed",
                                "Failed to generate fix suggestions: " + fixSuggestionResult.get("message"),
                                Map.of());
                        }
                    } else {
                        details = List.of();
                    }

                    return new WorkflowResult.TestResult(
                        status, perfScore, violationCounts,
                        WorkflowResult.PassThreshold.defaultThreshold(),
                        details);
                }
            }

        } catch (Exception e) {
            log.error("Failed to generate {} suggestions for {}", testType, componentName, e);
            sseEventPublisher.publishComponentStatus(sessionId, componentName,
                testType, "failed",
                "Error: " + e.getMessage(),
                Map.of());
        }

        return null;
    }

    /**
     * Group accessibility violations by file for the details structure.
     * Axe-core violations are grouped under the HTML file by default,
     * since axe tests the rendered DOM.
     * Enhanced to include actionable fix information from nodes.
     */
    private List<WorkflowResult.FileIssues> groupViolationsByFile(
            List<Map<String, Object>> violations,
            String htmlPath, String tsPath, String stylesPath,
            String aiSuggestion) {

        Map<String, List<WorkflowResult.IssueDetail>> fileMap = new LinkedHashMap<>();

        String primaryFile = htmlPath != null
            ? Path.of(htmlPath).getFileName().toString()
            : "component.html";

        for (Map<String, Object> violation : violations) {
            String id = (String) violation.get("id");
            String impact = (String) violation.getOrDefault("impact", "unknown");
            String help = (String) violation.getOrDefault("help", "");

            // Use the new enhanced 'nodes' structure instead of 'affectedNodes'
            Object nodesObj = violation.get("nodes");
            List<Map<String, Object>> nodes = (nodesObj instanceof List)
                ? (List<Map<String, Object>>) nodesObj
                : List.of();

            if (!nodes.isEmpty()) {
                for (Map<String, Object> node : nodes) {
                    String element = (String) node.getOrDefault("html", "");

                    // Extract actionable details from the node
                    Map<String, Object> actionableDetails = new HashMap<>();

                    // Include violation type
                    if (node.containsKey("violationType")) {
                        actionableDetails.put("violationType", node.get("violationType"));
                    }

                    // Include fix suggestions
                    if (node.containsKey("suggestedFix")) {
                        actionableDetails.put("suggestedFix", node.get("suggestedFix"));
                    }
                    if (node.containsKey("howToFix")) {
                        actionableDetails.put("howToFix", node.get("howToFix"));
                    }
                    if (node.containsKey("exampleFix")) {
                        actionableDetails.put("exampleFix", node.get("exampleFix"));
                    }

                    // Color contrast specific details
                    if (node.containsKey("foregroundColor")) {
                        actionableDetails.put("foregroundColor", node.get("foregroundColor"));
                        actionableDetails.put("backgroundColor", node.get("backgroundColor"));
                        actionableDetails.put("contrastRatio", node.get("contrastRatio"));
                        actionableDetails.put("expectedRatio", node.get("expectedRatio"));
                        if (node.containsKey("cssClass")) {
                            actionableDetails.put("cssClass", node.get("cssClass"));
                        }
                        if (node.containsKey("suggestedColorOptions")) {
                            actionableDetails.put("suggestedColorOptions", node.get("suggestedColorOptions"));
                        }
                    }

                    // Missing attributes
                    if (node.containsKey("missingAttribute")) {
                        actionableDetails.put("missingAttribute", node.get("missingAttribute"));
                    }
                    if (node.containsKey("missingAttributes")) {
                        actionableDetails.put("missingAttributes", node.get("missingAttributes"));
                    }

                    // Invalid attributes/values
                    if (node.containsKey("currentTabindex")) {
                        actionableDetails.put("currentTabindex", node.get("currentTabindex"));
                    }
                    if (node.containsKey("whyBad")) {
                        actionableDetails.put("whyBad", node.get("whyBad"));
                    }

                    // WCAG guideline
                    if (node.containsKey("wcagGuideline")) {
                        actionableDetails.put("wcagGuideline", node.get("wcagGuideline"));
                    }

                    // Target selector
                    if (node.containsKey("target")) {
                        actionableDetails.put("target", node.get("target"));
                    }

                    // Failure messages
                    if (node.containsKey("failureMessages")) {
                        actionableDetails.put("failureMessages", node.get("failureMessages"));
                    }

                    WorkflowResult.IssueDetail issue = new WorkflowResult.IssueDetail(
                        id, impact, 0, element, help, actionableDetails);
                    fileMap.computeIfAbsent(primaryFile, k -> new ArrayList<>()).add(issue);
                }
            } else {
                // No nodes, still record the violation with basic info
                WorkflowResult.IssueDetail issue = new WorkflowResult.IssueDetail(
                    id, impact, 0, "", help, Map.of());
                fileMap.computeIfAbsent(primaryFile, k -> new ArrayList<>()).add(issue);
            }
        }

        return fileMap.entrySet().stream()
            .map(e -> new WorkflowResult.FileIssues(e.getKey(), e.getValue()))
            .toList();
    }

    /**
     * Group performance warnings by file.
     * Performance warnings are primarily TS-related, so they are grouped under the TS file.
     */
    private List<WorkflowResult.FileIssues> groupPerformanceWarningsByFile(
            List<String> warnings, String tsPath, String aiSuggestion) {

        String primaryFile = tsPath != null
            ? Path.of(tsPath).getFileName().toString()
            : "component.ts";

        List<WorkflowResult.IssueDetail> issues = new ArrayList<>();
        for (String warning : warnings) {
            // Determine severity based on warning content
            String severity;
            String lower = warning.toLowerCase();
            if (lower.contains("memory leak") || lower.contains("blocking") || lower.contains("infinite")) {
                severity = "critical";
            } else if (lower.contains("excessive") || lower.contains("large") || lower.contains("no lazy")) {
                severity = "serious";
            } else if (lower.contains("missing") || lower.contains("trackby") || lower.contains("change detection")) {
                severity = "moderate";
            } else {
                severity = "minor";
            }

            issues.add(new WorkflowResult.IssueDetail(
                "performance-warning", severity, 0, "", warning));
        }

        if (issues.isEmpty()) {
            return List.of();
        }

        return List.of(new WorkflowResult.FileIssues(primaryFile, issues));
    }

    /**
     * Build the final workflow summary from all component results.
     */
    private WorkflowResult.WorkflowSummary buildWorkflowSummary(
            String repoId,
            List<WorkflowResult.ComponentTestResult> componentResults,
            List<String> testList) {

        int totalComponents = componentResults.size();
        Map<String, WorkflowResult.TestSummary> testSummaries = new LinkedHashMap<>();
        List<WorkflowResult.ComponentScoreEntry> componentScores = new ArrayList<>();

        String overallStatus = "pass";

        for (String testType : testList) {
            int sumScore = 0;
            int count = 0;
            int passed = 0, warned = 0, failed = 0;

            for (WorkflowResult.ComponentTestResult cr : componentResults) {
                WorkflowResult.TestResult tr = "accessibility".equals(testType)
                    ? cr.accessibility() : cr.performance();
                if (tr == null) continue;

                count++;
                sumScore += tr.score();
                switch (tr.status()) {
                    case "pass" -> passed++;
                    case "warning" -> warned++;
                    case "fail" -> failed++;
                }
                overallStatus = ScoringUtils.worstStatus(overallStatus, tr.status());
            }

            int avgScore = count > 0 ? sumScore / count : 0;
            String testStatus = ScoringUtils.statusFromScore(avgScore);

            testSummaries.put(testType, new WorkflowResult.TestSummary(
                testStatus, avgScore, passed, warned, failed));
        }

        // Build component score entries
        for (WorkflowResult.ComponentTestResult cr : componentResults) {
            WorkflowResult.ScoreStatus a11y = cr.accessibility() != null
                ? new WorkflowResult.ScoreStatus(cr.accessibility().score(), cr.accessibility().status())
                : null;
            WorkflowResult.ScoreStatus perf = cr.performance() != null
                ? new WorkflowResult.ScoreStatus(cr.performance().score(), cr.performance().status())
                : null;

            componentScores.add(new WorkflowResult.ComponentScoreEntry(
                cr.component().fullName(), a11y, perf));
        }

        // Overall score = average of all test type average scores
        int overallScore = testSummaries.values().stream()
            .mapToInt(WorkflowResult.TestSummary::averageScore)
            .sum() / Math.max(1, testSummaries.size());

        WorkflowResult.Summary summary = new WorkflowResult.Summary(
            totalComponents, overallStatus, overallScore, testSummaries, componentScores);

        return new WorkflowResult.WorkflowSummary(repoId, summary);
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
