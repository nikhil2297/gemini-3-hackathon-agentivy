package com.agentivy.backend.controller;

import com.agentivy.backend.service.SessionContext;
import com.agentivy.backend.service.SseEventPublisher;
import com.agentivy.backend.tools.testing.AccessibilityTestingTool;
import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

/**
 * Debug controller to test accessibility events publishing via SSE.
 */
@Slf4j
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class DebugTestController {

    private final AccessibilityTestingTool accessibilityTestingTool;
    private final SseEventPublisher sseEventPublisher;

    /**
     * Test endpoint to verify accessibility events are published via SSE.
     *
     * Usage: GET http://localhost:8080/api/debug/test-accessibility-sse?url=http://localhost:4200/agent-ivy-harness
     */
    @GetMapping(value = "/test-accessibility-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter testAccessibilityWithSSE(@RequestParam String url) {
        String sessionId = "debug-session-" + System.currentTimeMillis();
        log.info("Starting SSE stream for accessibility test: {} (sessionId: {})", url, sessionId);

        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L); // 5 minute timeout

        // Register emitter with publisher
        sseEventPublisher.registerEmitter(sessionId, emitter);

        // Send initial "started" event
        sseEventPublisher.publishStarted(sessionId, url);

        // Execute accessibility test asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                // Set session context for this thread
                SessionContext.setSessionId(sessionId);

                log.info("Starting accessibility test for session: {}", sessionId);
                sseEventPublisher.publishProgress(sessionId, "Starting accessibility test...", "testing");

                // Run the accessibility test - this should publish component_status events
                ImmutableMap<String, Object> result = accessibilityTestingTool
                    .runAccessibilityTest(url, "AA")
                    .blockingGet();

                // Send completion event
                String status = (String) result.get("status");
                Integer violations = (Integer) result.getOrDefault("totalViolations", 0);
                String summary = String.format("Accessibility test completed: %s (%d violations)",
                    status, violations);

                sseEventPublisher.publishCompleted(sessionId, summary, result);

            } catch (Exception e) {
                log.error("SSE stream error for session: {}", sessionId, e);
                sseEventPublisher.publishError(sessionId, e.getMessage(), "testing");
            } finally {
                SessionContext.clear();
            }
        });

        return emitter;
    }
}
