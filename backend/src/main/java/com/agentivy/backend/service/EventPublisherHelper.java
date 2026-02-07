package com.agentivy.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Helper class for tools to publish SSE events without direct dependency on SseEventPublisher.
 * Uses SessionContext to automatically get the current session ID.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisherHelper {

    private final SseEventPublisher sseEventPublisher;

    /**
     * Publish a progress event for the current session.
     */
    public void publishProgress(String message, String phase) {
        String sessionId = SessionContext.getSessionId();
        if (sessionId != null) {
            sseEventPublisher.publishProgress(sessionId, message, phase);
        }
    }

    /**
     * Publish a tool call event for the current session.
     */
    public void publishToolCall(String toolName, String description) {
        String sessionId = SessionContext.getSessionId();
        if (sessionId != null) {
            sseEventPublisher.publishToolCall(sessionId, toolName, description);
        }
    }

    /**
     * Publish a component result event for the current session.
     */
    public void publishComponentResult(String componentName, String status, Map<String, Object> testResults) {
        String sessionId = SessionContext.getSessionId();
        if (sessionId != null) {
            sseEventPublisher.publishComponentResult(sessionId, componentName, status, testResults);
        }
    }

    /**
     * Publish a component status event for the current session.
     * Use this to track component progress through different tools.
     *
     * @param componentName Name of the component being processed
     * @param tool Tool being used (e.g., "accessibility", "performance", "harness", "dev-server")
     * @param status Status of the operation (e.g., "starting", "in-progress", "completed", "failed")
     * @param message Descriptive message about the status
     * @param metadata Additional context (e.g., violations count, metrics)
     */
    public void publishComponentStatus(String componentName, String tool, String status, String message, Map<String, Object> metadata) {
        String sessionId = SessionContext.getSessionId();
        if (sessionId != null) {
            sseEventPublisher.publishComponentStatus(sessionId, componentName, tool, status, message, metadata);
        }
    }

    /**
     * Convenience method for component status without metadata.
     */
    public void publishComponentStatus(String componentName, String tool, String status, String message) {
        publishComponentStatus(componentName, tool, status, message, Map.of());
    }

    /**
     * Publish an error event for the current session.
     */
    public void publishError(String message, String phase) {
        String sessionId = SessionContext.getSessionId();
        if (sessionId != null) {
            sseEventPublisher.publishError(sessionId, message, phase);
        }
    }

    /**
     * Check if we have an active session context.
     */
    public boolean hasActiveSession() {
        return SessionContext.hasSessionId();
    }
}
