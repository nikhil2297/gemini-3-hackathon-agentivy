package com.agentivy.backend.controller;

import com.agentivy.backend.agents.ComponentAnalyzerAgentFactory;
import com.agentivy.backend.dto.AgentEvent;
import com.agentivy.backend.service.SessionContext;
import com.agentivy.backend.service.SseEventPublisher;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Controller for autonomous agent-driven workflows.
 * The agent orchestrates the entire test-fix-verify process autonomously using available tools.
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AutonomousAgentController {

    private final ComponentAnalyzerAgentFactory agentFactory;
    private final SseEventPublisher sseEventPublisher;

    /**
     * Autonomous repository analysis and fixing workflow.
     * The agent autonomously:
     * 1. Scans the repository for Angular components
     * 2. Analyzes each component for issues
     * 3. Applies fixes automatically
     * 4. Re-tests to verify fixes
     * 5. Generates a comprehensive report
     *
     * @param request Repository path and optional constraints
     * @return Agent's comprehensive analysis and fix report
     */
    @PostMapping("/analyze-repo")
    public ResponseEntity<Map<String, Object>> analyzeRepository(@RequestBody AnalyzeRepoRequest request) {
        log.info("Starting autonomous repository analysis for: {}", request.repoPath);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoPath", request.repoPath);
        result.put("timestamp", System.currentTimeMillis());

        try {
            // Create runner with fresh agent (ensures tools are loaded)
            InMemoryRunner analyzerRunner = agentFactory.createRunner();

            // Create agent session
            Session session = analyzerRunner.sessionService()
                .createSession(analyzerRunner.appName(), "repo-analysis-" + System.currentTimeMillis())
                .blockingGet();

            // Build autonomous prompt
            String prompt = buildAutonomousPrompt(request);

            log.info("======================================");
            log.info("AUTONOMOUS AGENT PROMPT:");
            log.info(prompt);
            log.info("======================================");

            log.info("Executing autonomous agent...");
            Content userMsg = Content.fromParts(Part.fromText(prompt));

            // Collect all agent events
            List<Map<String, Object>> events = new ArrayList<>();
            AtomicReference<String> finalReport = new AtomicReference<>("");

            Flowable<Event> eventStream = analyzerRunner.runAsync(
                session.userId(),
                session.id(),
                userMsg
            );

            // Process events and track agent actions
            eventStream.blockingForEach(event -> {
                Map<String, Object> eventData = new LinkedHashMap<>();
                eventData.put("timestamp", System.currentTimeMillis());
                eventData.put("author", event.author() != null ? event.author() : "system");

                String contentText = event.content().map(this::extractTextFromContent).orElse("");
                eventData.put("content", truncate(contentText, 200));

                if (event.finalResponse()) {
                    finalReport.set(event.stringifyContent());
                    log.info("Agent completed analysis");
                }

                log.debug("Event [{}]: {}", event.author(), truncate(contentText, 100));
                events.add(eventData);
            });

            result.put("status", "success");
            result.put("message", "Autonomous analysis completed");
            result.put("finalReport", finalReport.get());
            result.put("events", events);
            result.put("totalEvents", events.size());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Autonomous analysis failed", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * SSE streaming endpoint for autonomous repository analysis.
     * Streams real-time events as the agent discovers components, runs tests, and applies fixes.
     *
     * @param repoPath Repository path to analyze
     * @param maxComponents Optional maximum number of components to analyze
     * @param tests Optional list of tests to run (accessibility, performance)
     * @param maxIterations Optional maximum fix iterations per component
     * @return SseEmitter that streams AgentEvent objects
     */
    @GetMapping(value = "/analyze-repo-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeRepositoryStream(
        @RequestParam String repoPath,
        @RequestParam(required = false) Integer maxComponents,
        @RequestParam(required = false, defaultValue = "accessibility,performance") String tests,
        @RequestParam(required = false) Integer maxIterations
    ) {
        String sessionId = "session-" + System.currentTimeMillis();
        log.info("Starting SSE stream for repository analysis: {} (sessionId: {})", repoPath, sessionId);

        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 minute timeout

        // Register emitter with publisher
        sseEventPublisher.registerEmitter(sessionId, emitter);

        // Send initial "started" event
        sseEventPublisher.publishStarted(sessionId, repoPath);

        // Execute agent workflow asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                // Set session context for this thread
                SessionContext.setSessionId(sessionId);

                // Create runner with fresh agent
                InMemoryRunner analyzerRunner = agentFactory.createRunner();
                Session session = analyzerRunner.sessionService()
                    .createSession(analyzerRunner.appName(), sessionId)
                    .blockingGet();

                // Build prompt - convert comma-separated tests to list
                List<String> testList = tests != null && !tests.isEmpty()
                    ? Arrays.asList(tests.split(","))
                    : List.of("accessibility", "performance");

                AnalyzeRepoRequest request = new AnalyzeRepoRequest(
                    repoPath, maxComponents, testList, maxIterations
                );
                String prompt = buildAutonomousPrompt(request);
                Content userMsg = Content.fromParts(Part.fromText(prompt));

                log.info("Starting agent execution for session: {}", sessionId);
                sseEventPublisher.publishProgress(sessionId, "Initializing agent workflow...", "initializing");

                // Stream agent events
                Flowable<Event> eventStream = analyzerRunner.runAsync(
                    session.userId(),
                    session.id(),
                    userMsg
                );

                eventStream.blockingForEach(event -> {
                    // Convert ADK Event to AgentEvent and publish via SSE
                    AgentEvent agentEvent = convertToAgentEvent(event);

                    if (agentEvent != null) {
                        sseEventPublisher.publishEvent(sessionId, agentEvent);
                    }

                    if (event.finalResponse()) {
                        // Send completion event
                        String summary = extractTextFromContent(event.content().orElse(null));
                        sseEventPublisher.publishCompleted(sessionId, summary, extractFinalReport(event));
                    }
                });

            } catch (Exception e) {
                log.error("SSE stream error for session: {}", sessionId, e);
                sseEventPublisher.publishError(sessionId, e.getMessage(), "execution");
            } finally {
                SessionContext.clear();
            }
        });

        return emitter;
    }

    /**
     * Analyze a single component autonomously.
     */
    @PostMapping("/analyze-component")
    public ResponseEntity<Map<String, Object>> analyzeComponent(@RequestBody AnalyzeComponentRequest request) {
        log.info("Starting autonomous component analysis: {}", request.componentClassName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("componentClassName", request.componentClassName);
        result.put("timestamp", System.currentTimeMillis());

        try {
            // Create runner with fresh agent (ensures tools are loaded)
            InMemoryRunner analyzerRunner = agentFactory.createRunner();

            Session session = analyzerRunner.sessionService()
                .createSession(analyzerRunner.appName(), "component-analysis-" + System.currentTimeMillis())
                .blockingGet();

            StringBuilder testsDescription = new StringBuilder();
            if (request.tests.contains("accessibility")) {
                testsDescription.append("runAccessibilityTest()");
            }
            if (request.tests.contains("performance")) {
                if (testsDescription.length() > 0) testsDescription.append(" ");
                testsDescription.append("analyzeComponentPerformance()");
            }

            String prompt = String.format("""
                Analyze and fix this Angular component autonomously:

                Repository: %s
                Component: %s
                Tests to run: %s
                Max iterations: %d

                WORKFLOW:
                1. Read the component files using readComponentFiles()
                2. Create test harness with loadComponentForTesting()
                3. Start dev server with prepareAndStartServer()
                4. Run tests: %s
                5. If issues found, apply fixes using fixAccessibilityViolations() and/or fixPerformanceIssues()
                6. Restart server and re-test
                7. Repeat until all tests pass or max iterations reached
                8. Stop server when done
                9. Provide comprehensive report

                Be thorough and autonomous. Make decisions based on test results.
                """,
                request.repoPath,
                request.componentClassName,
                request.tests,
                request.maxIterations != null ? request.maxIterations : 3,
                testsDescription.toString()
            );

            Content userMsg = Content.fromParts(Part.fromText(prompt));
            AtomicReference<String> finalReport = new AtomicReference<>("");

            Flowable<Event> events = analyzerRunner.runAsync(session.userId(), session.id(), userMsg);

            events.blockingForEach(event -> {
                if (event.finalResponse()) {
                    finalReport.set(event.stringifyContent());
                }
            });

            result.put("status", "success");
            result.put("message", "Component analysis completed");
            result.put("report", finalReport.get());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Component analysis failed", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Build autonomous prompt for repository analysis.
     */
    private String buildAutonomousPrompt(AnalyzeRepoRequest request) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("START AUTONOMOUS ANGULAR COMPONENT ANALYSIS\n\n");
        prompt.append("Repository Path: ").append(request.repoPath).append("\n");

        if (request.maxComponents != null && request.maxComponents > 0) {
            prompt.append("Max Components to Analyze: ").append(request.maxComponents).append("\n");
        } else {
            prompt.append("Analyze: ALL components found\n");
        }

        List<String> tests = request.tests != null && !request.tests.isEmpty() ?
            request.tests : List.of("accessibility", "performance");
        prompt.append("Tests to Run: ").append(String.join(", ", tests)).append("\n");
        prompt.append("Max Fix Iterations: ").append(request.maxIterations != null ? request.maxIterations : 3).append("\n\n");

        prompt.append("EXECUTE THIS WORKFLOW NOW:\n\n");
        prompt.append("STEP 1: Call scanForAngularComponents(\"").append(request.repoPath).append("\")\n");
        prompt.append("   This will find all Angular components in the project.\n\n");

        prompt.append("STEP 2: For EACH component found:\n");
        prompt.append("   a. Call readComponentFiles(tsPath, htmlPath, cssPath) to read component code\n");
        prompt.append("   b. Analyze: Extract @Input() properties and service dependencies\n");
        prompt.append("   c. Call loadComponentForTesting() with:\n");
        prompt.append("      - componentClassName\n");
        prompt.append("      - mockData for inputs (e.g. \"mockTasks = [{id: 1}]\")\n");
        prompt.append("      - serviceMocks (e.g. \"TaskService|getTasks|mockTasks|true\")\n");
        prompt.append("   d. Call prepareAndStartServer(\"").append(request.repoPath).append("\", 4200)\n");
        prompt.append("   e. Wait for server to start, then run tests:\n");

        if (tests.contains("accessibility")) {
            prompt.append("      - Call runAccessibilityTest(componentUrl, wcagLevel=\"AA\")\n");
        }
        if (tests.contains("performance")) {
            prompt.append("      - Call analyzeComponentPerformance(componentUrl, componentSelector)\n");
        }

        prompt.append("   f. IF issues found:\n");
        if (tests.contains("accessibility")) {
            prompt.append("      - Call fixAccessibilityViolations(repoPath, componentClassName, violations)\n");
        }
        if (tests.contains("performance")) {
            prompt.append("      - Call fixPerformanceIssues(repoPath, componentClassName, warnings, metrics)\n");
        }
        prompt.append("   g. Call stopServer(\"").append(request.repoPath).append("\")\n");
        prompt.append("   h. If fixes applied: Restart server and re-test to verify\n\n");

        prompt.append("STEP 3: After ALL components analyzed, provide final report:\n");
        prompt.append("   - Total components found\n");
        prompt.append("   - Components analyzed\n");
        prompt.append("   - Components with issues (before fixes)\n");
        prompt.append("   - Components fixed successfully\n");
        prompt.append("   - Remaining issues\n");
        prompt.append("   - Per-component breakdown\n\n");

        prompt.append("BEGIN EXECUTION NOW. Start with scanForAngularComponents().\n");

        return prompt.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /**
     * Extract text content from Event content.
     */
    private String extractTextFromContent(com.google.genai.types.Content content) {
        if (content == null) {
            return "";
        }

        return content.parts().map(parts -> {
            StringBuilder text = new StringBuilder();
            for (com.google.genai.types.Part part : parts) {
                if (part.text() != null && !part.text().isEmpty()) {
                    text.append(part.text()).append(" ");
                }
            }
            return text.toString().trim();
        }).orElse("");
    }

    /**
     * Convert ADK Event to AgentEvent for SSE streaming.
     */
    private AgentEvent convertToAgentEvent(Event event) {
        String contentText = event.content()
            .map(this::extractTextFromContent)
            .orElse("");

        // Detect tool calls from event
        if (contentText.contains("Calling tool:") || contentText.contains("scanForAngularComponents") ||
            contentText.contains("loadComponentForTesting") || contentText.contains("prepareAndStartServer") ||
            contentText.contains("runAccessibilityTest") || contentText.contains("analyzeComponentPerformance") ||
            contentText.contains("fixAccessibilityViolations") || contentText.contains("fixPerformanceIssues")) {
            String toolName = extractToolName(contentText);
            return new AgentEvent.ToolCall(
                toolName,
                Map.of("description", contentText),
                System.currentTimeMillis()
            );
        }

        // Detect component-specific progress
        if (contentText.contains("Component:") || contentText.contains("Testing") ||
            contentText.contains("component") && (contentText.contains("found") || contentText.contains("analyzed"))) {
            String componentName = extractComponentName(contentText);
            return new AgentEvent.ComponentResult(
                componentName,
                "analyzing",
                Map.of("message", contentText),
                System.currentTimeMillis()
            );
        }

        // Detect phase from content
        String phase = detectPhase(contentText);

        // General progress message
        return new AgentEvent.Progress(
            contentText,
            phase,
            0,
            0,
            System.currentTimeMillis()
        );
    }

    /**
     * Get event name for SSE event type.
     */
    private String getEventName(AgentEvent event) {
        return switch (event) {
            case AgentEvent.Started s -> "started";
            case AgentEvent.Progress p -> "progress";
            case AgentEvent.ToolCall t -> "tool_call";
            case AgentEvent.ComponentResult c -> "component_result";
            case AgentEvent.ComponentStatus cs -> "component_status";
            case AgentEvent.FixSuggestion fs -> "fix_suggestion";
            case AgentEvent.Completed comp -> "completed";
            case AgentEvent.Error e -> "error";
            case AgentEvent.WorkflowStarted ws -> "start";
            case AgentEvent.WorkflowComponentResult wcr -> "component-result";
            case AgentEvent.WorkflowSummaryEvent wse -> "summary";
            case AgentEvent.WorkflowDone wd -> "done";
        };
    }

    /**
     * Extract tool name from content text.
     */
    private String extractToolName(String contentText) {
        if (contentText.contains("scanForAngularComponents")) return "scanForAngularComponents";
        if (contentText.contains("loadComponentForTesting")) return "loadComponentForTesting";
        if (contentText.contains("prepareAndStartServer")) return "prepareAndStartServer";
        if (contentText.contains("runAccessibilityTest")) return "runAccessibilityTest";
        if (contentText.contains("analyzeComponentPerformance")) return "analyzeComponentPerformance";
        if (contentText.contains("fixAccessibilityViolations")) return "fixAccessibilityViolations";
        if (contentText.contains("fixPerformanceIssues")) return "fixPerformanceIssues";
        if (contentText.contains("stopServer")) return "stopServer";
        if (contentText.contains("readComponentFiles")) return "readComponentFiles";
        return "unknown";
    }

    /**
     * Extract component name from content text.
     */
    private String extractComponentName(String contentText) {
        // Try to extract component name from patterns like "Component: TaskListComponent" or "Testing TaskListComponent"
        if (contentText.contains("Component:")) {
            String[] parts = contentText.split("Component:");
            if (parts.length > 1) {
                String name = parts[1].trim().split("\\s+")[0];
                return name.isEmpty() ? "unknown" : name;
            }
        }
        if (contentText.contains("Testing")) {
            String[] parts = contentText.split("Testing");
            if (parts.length > 1) {
                String name = parts[1].trim().split("\\s+")[0];
                return name.isEmpty() ? "unknown" : name;
            }
        }
        return "unknown";
    }

    /**
     * Detect phase from content text.
     */
    private String detectPhase(String contentText) {
        String lowerContent = contentText.toLowerCase();
        if (lowerContent.contains("scan") || lowerContent.contains("discover")) return "scanning";
        if (lowerContent.contains("test") || lowerContent.contains("audit")) return "testing";
        if (lowerContent.contains("fix") || lowerContent.contains("repair")) return "fixing";
        if (lowerContent.contains("verify") || lowerContent.contains("re-test")) return "verifying";
        return "analyzing";
    }

    /**
     * Extract final report from event.
     */
    private Map<String, Object> extractFinalReport(Event event) {
        String contentText = event.content()
            .map(this::extractTextFromContent)
            .orElse("");

        return Map.of(
            "summary", contentText,
            "timestamp", System.currentTimeMillis()
        );
    }

    // Request DTOs
    public record AnalyzeRepoRequest(
        String repoPath,
        Integer maxComponents,
        List<String> tests,
        Integer maxIterations
    ) {}

    public record AnalyzeComponentRequest(
        String repoPath,
        String componentClassName,
        List<String> tests,
        Integer maxIterations
    ) {}
}
