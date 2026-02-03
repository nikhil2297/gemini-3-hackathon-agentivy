package com.agentivy.backend.controller;

import com.agentivy.backend.controller.dto.ComponentAnalysisRequest;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Flowable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

/**
 * Agent Controller - Run the AI agent for automated analysis
 *
 * This is the main entry point for agent-driven accessibility analysis.
 * The agent will automatically call tools in sequence to analyze components.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AgentController {

    private final LlmAgent componentAnalyzerAgent;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Extract text content from an Optional<Content> object.
     * Handles the nested Optional structure properly.
     */
    private String extractTextFromContent(java.util.Optional<Content> contentOpt) {
        if (contentOpt == null || contentOpt.isEmpty()) {
            return "";
        }

        Content content = contentOpt.get();
        return content.parts().orElse(List.of()).stream()
                .filter(part -> part.text().isPresent())
                .map(part -> part.text().get())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Extract a summary of the content including function calls.
     * Returns either text content or a description of function calls.
     */
    private String extractContentSummary(java.util.Optional<Content> contentOpt) {
        if (contentOpt == null || contentOpt.isEmpty()) {
            return "";
        }

        Content content = contentOpt.get();
        List<Part> parts = content.parts().orElse(List.of());

        StringBuilder summary = new StringBuilder();

        for (Part part : parts) {
            // Add text content
            if (part.text().isPresent()) {
                summary.append(part.text().get()).append("\n");
            }
            // Add function call information
            else if (part.functionCall().isPresent()) {
                var functionCall = part.functionCall().get();
                String functionName = functionCall.name().orElse("unknown");
                summary.append("[Calling function: ").append(functionName).append("]\n");
            }
            // Add function response summary
            else if (part.functionResponse().isPresent()) {
                var functionResponse = part.functionResponse().get();
                String functionName = functionResponse.name().orElse("unknown");
                summary.append("[Function ").append(functionName).append(" completed]\n");
            }
        }

        return summary.toString().trim();
    }

    /**
     * Run the agent with a natural language prompt.
     * Returns all events when complete.
     *
     * POST /api/v1/agent/analyze
     * {
     *   "prompt": "Analyze the TaskListComponent in /path/to/angular-app for accessibility issues"
     * }
     *
     * Example prompts:
     * - "Clone https://github.com/user/repo and analyze all components for accessibility"
     * - "Analyze TaskListComponent at /tmp/agentivy/repo-123 for WCAG AA compliance"
     * - "Scan /path/to/project for Angular components and run accessibility audit on UserProfileComponent"
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> request) {
        String prompt = request.get("prompt");

        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Missing 'prompt' in request body. " +
                            "Example: 'Analyze TaskListComponent at /path/to/project for accessibility issues'"
            ));
        }

        log.info("Running agent with prompt: {}", prompt);

        try {
            InMemoryRunner runner = new InMemoryRunner(componentAnalyzerAgent);
            Session session = runner.sessionService().createSession(
                    componentAnalyzerAgent.name(),
                    "user-" + System.currentTimeMillis()
            ).blockingGet();

            List<Map<String, Object>> events = new ArrayList<>();
            StringBuilder finalResponse = new StringBuilder();
            Content userMessage = Content.fromParts(Part.fromText(prompt));
            Flowable<Event> eventStream = runner.runAsync(session.userId(), session.id(), userMessage);

            // Collect all events
            eventStream.blockingForEach(event -> {
                log.debug("Event [{}]: {}", event.author(), event.content());

                String contentText = extractTextFromContent(event.content());
                String contentSummary = extractContentSummary(event.content());

                Map<String, Object> eventMap = Map.of(
                        "author", event.author() != null ? event.author() : "unknown",
                        "content", contentSummary,
                        "timestamp", System.currentTimeMillis()
                );
                events.add(eventMap);

                // Capture agent's final response (only text, not function calls)
                if ("ComponentAnalyzerAgent".equals(event.author()) && !contentText.isEmpty()) {
                    finalResponse.append(contentText).append("\n");
                }
            });

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "sessionId", session.id(),
                    "agentResponse", finalResponse.toString(),
                    "eventCount", events.size(),
                    "events", events
            ));

        } catch (Exception e) {
            log.error("Agent execution failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Agent execution failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Run the agent with Server-Sent Events for real-time streaming.
     *
     * GET /api/v1/agent/analyze/stream?prompt=...
     *
     * Use this endpoint to see agent progress in real-time.
     */
    @GetMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@RequestParam String prompt) {
        SseEmitter emitter = new SseEmitter(300000L); // 5 minute timeout

        executor.execute(() -> {
            try {
                log.info("Starting streaming analysis: {}", prompt);

                InMemoryRunner runner = new InMemoryRunner(componentAnalyzerAgent);
                Session session = runner.sessionService().createSession(
                        componentAnalyzerAgent.name(),
                        "user-" + System.currentTimeMillis()
                ).blockingGet();

                // Send session info
                emitter.send(SseEmitter.event()
                        .name("session")
                        .data(Map.of("sessionId", session.id())));

                Content userMessage = Content.fromParts(Part.fromText(prompt));
                Flowable<Event> eventStream = runner.runAsync(session.userId(), session.id(), userMessage);

                eventStream.blockingForEach(event -> {
                    try {
                        String author = event.author() != null ? event.author() : "system";
                        String content = extractContentSummary(event.content());

                        emitter.send(SseEmitter.event()
                                .name("event")
                                .data(Map.of(
                                        "author", author,
                                        "content", content,
                                        "timestamp", System.currentTimeMillis()
                                )));

                    } catch (Exception e) {
                        log.warn("Error sending SSE event: {}", e.getMessage());
                    }
                });

                emitter.send(SseEmitter.event()
                        .name("complete")
                        .data(Map.of("status", "complete")));
                emitter.complete();

            } catch (Exception e) {
                log.error("Streaming analysis failed", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("error", e.getMessage())));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Quick analysis endpoint - just provide a path and component name.
     *
     * POST /api/v1/agent/quick
     * {
     *   "projectPath": "/path/to/angular-project",
     *   "componentName": "TaskListComponent"
     * }
     */
    @PostMapping("/quick")
    public ResponseEntity<Map<String, Object>> quickAnalysis(@RequestBody Map<String, String> request) {
        String projectPath = request.get("projectPath");
        String componentName = request.get("componentName");

        if (projectPath == null || projectPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Missing 'projectPath'"
            ));
        }

        if (componentName == null || componentName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Missing 'componentName'"
            ));
        }

        String prompt = String.format(
                "Analyze the %s component in the Angular project at %s for WCAG AA accessibility issues. " +
                        "Create a test harness, start the dev server, run the accessibility audit, " +
                        "report all violations found, and stop the server when done.",
                componentName, projectPath
        );

        return analyze(Map.of("prompt", prompt));
    }

    /**
     * Full repository analysis - analyze selected components.
     *
     * POST /api/v1/agent/repo
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
    @PostMapping("/repo")
    public ResponseEntity<Map<String, Object>> analyzeRepo(@RequestBody ComponentAnalysisRequest request) {
        String repoPath = request.repoPath();
        String repoId = request.repoId();
        List<ComponentAnalysisRequest.ComponentInfo> components = request.component();

        // Validate required fields
        if (repoPath == null || repoPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Missing 'repoPath'"
            ));
        }

        if (components == null || components.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Missing 'component' array or empty"
            ));
        }

        // Get tests array, default to ["accessibility"] if not provided
        List<String> tests = request.tests();
        if (tests == null || tests.isEmpty()) {
            tests = List.of("accessibility");
        }

        // Build prompt for all components
        String prompt = buildPromptForComponents(repoPath, components, tests);

        log.info("Analyzing {} components with tests {} in repo: {} (id: {})",
            components.size(), tests, repoPath, repoId);

        return analyze(Map.of("prompt", prompt));
    }

    /**
     * Builds a prompt for analyzing multiple components.
     */
    private String buildPromptForComponents(String repoPath,
            List<ComponentAnalysisRequest.ComponentInfo> components,
            List<String> tests) {

        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("Project path: %s\n\n", repoPath));
        prompt.append(String.format("Tests to run: %s\n\n", String.join(", ", tests)));
        prompt.append("Analyze the following components:\n\n");

        for (int i = 0; i < components.size(); i++) {
            var comp = components.get(i);
            prompt.append(String.format("%d. Component: %s\n", i + 1, comp.name()));
            prompt.append(String.format("   - Location: %s\n", comp.relativePath()));
            prompt.append(String.format("   - TS: %s\n", comp.tsPath()));
            prompt.append(String.format("   - HTML: %s\n", comp.htmlPath() != null ? comp.htmlPath() : "none"));
            prompt.append(String.format("   - Styles: %s\n\n", comp.stylesPath() != null ? comp.stylesPath() : "none"));
        }

        prompt.append("For each component, run the following tests:\n");
        for (String test : tests) {
            switch (test.toLowerCase()) {
                case "accessibility" -> prompt.append("- accessibility: Run WCAG AA audit, create test harness, report violations\n");
                case "performance" -> prompt.append("- performance: Run performance audit and report issues\n");
                case "unit" -> prompt.append("- unit: Generate and run unit tests\n");
                case "e2e" -> prompt.append("- e2e: Generate and run end-to-end tests\n");
            }
        }

        return prompt.toString();
    }

    /**
     * Health check for agent.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "agentName", componentAnalyzerAgent.name(),
                "model", componentAnalyzerAgent.model(),
                "endpoints", Map.of(
                        "analyze", "POST /api/v1/agent/analyze - Full agent analysis with custom prompt",
                        "stream", "GET /api/v1/agent/analyze/stream?prompt=... - Real-time streaming",
                        "quick", "POST /api/v1/agent/quick - Quick single component analysis",
                        "repo", "POST /api/v1/agent/repo - Full repository analysis"
                )
        ));
    }
}