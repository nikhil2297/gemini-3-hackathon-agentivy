package com.agentivy.backend.service;

import com.agentivy.backend.dto.AgentEvent;
import com.agentivy.backend.dto.WorkflowResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central SSE event publisher that manages multiple SSE connections
 * and broadcasts events to all active clients.
 */
@Slf4j
@Service
public class SseEventPublisher {

    // Map of sessionId -> List of SseEmitters for that session
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> sessionEmitters = new ConcurrentHashMap<>();

    /**
     * Register a new SSE emitter for a session.
     *
     * @param sessionId Unique session identifier
     * @param emitter The SseEmitter to register
     */
    public void registerEmitter(String sessionId, SseEmitter emitter) {
        sessionEmitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        log.info("Registered SSE emitter for session: {}", sessionId);

        // Send initial connection event
        try {
            emitter.send(SseEmitter.event()
                .name("connected")
                .data(Map.of(
                    "sessionId", sessionId,
                    "status", "connected",
                    "timestamp", System.currentTimeMillis()
                )));
        } catch (IOException e) {
            log.warn("Failed to send initial connection event for session: {}", sessionId, e);
        }

        // Auto-cleanup on completion or timeout
        emitter.onCompletion(() -> {
            removeEmitter(sessionId, emitter);
            log.info("SSE emitter completed for session: {}", sessionId);
        });

        emitter.onTimeout(() -> {
            removeEmitter(sessionId, emitter);
            log.warn("SSE emitter timed out for session: {}", sessionId);
        });

        emitter.onError((ex) -> {
            removeEmitter(sessionId, emitter);
            log.error("SSE emitter error for session: {}", sessionId, ex);
        });
    }

    /**
     * Remove an emitter from a session.
     */
    private void removeEmitter(String sessionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                sessionEmitters.remove(sessionId);
            }
        }
    }

    /**
     * Publish an event to all emitters for a specific session.
     *
     * @param sessionId Session identifier
     * @param event The AgentEvent to publish
     */
    public void publishEvent(String sessionId, AgentEvent event) {
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No emitters registered for session: {}", sessionId);
            return;
        }

        String eventName = getEventName(event);
        log.debug("Publishing event '{}' to {} emitter(s) for session: {}", eventName, emitters.size(), sessionId);

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(event)
                );
            } catch (IOException e) {
                log.error("Failed to send event to emitter for session: {}", sessionId, e);
                removeEmitter(sessionId, emitter);
            }
        }
    }

    /**
     * Publish a started event.
     */
    public void publishStarted(String sessionId, String repoPath) {
        publishEvent(sessionId, new AgentEvent.Started(
            sessionId,
            repoPath,
            System.currentTimeMillis()
        ));
    }

    /**
     * Publish a progress event.
     */
    public void publishProgress(String sessionId, String message, String phase) {
        publishEvent(sessionId, new AgentEvent.Progress(
            message,
            phase,
            0,
            0,
            System.currentTimeMillis()
        ));
    }

    /**
     * Publish a tool call event.
     */
    public void publishToolCall(String sessionId, String toolName, String description) {
        publishEvent(sessionId, new AgentEvent.ToolCall(
            toolName,
            Map.of("description", description),
            System.currentTimeMillis()
        ));
    }

    /**
     * Publish a component result event.
     */
    public void publishComponentResult(String sessionId, String componentName, String status, Map<String, Object> testResults) {
        publishEvent(sessionId, new AgentEvent.ComponentResult(
            componentName,
            status,
            testResults,
            System.currentTimeMillis()
        ));
    }

    /**
     * Publish a component status event.
     */
    public void publishComponentStatus(String sessionId, String componentName, String tool, String status, String message, Map<String, Object> metadata) {
        publishEvent(sessionId, new AgentEvent.ComponentStatus(
            componentName,
            tool,
            status,
            message,
            metadata,
            System.currentTimeMillis()
        ));
    }

    /**
     * Publish a completion event and complete all emitters.
     */
    public void publishCompleted(String sessionId, String summary, Map<String, Object> finalReport) {
        publishEvent(sessionId, new AgentEvent.Completed(
            summary,
            finalReport,
            System.currentTimeMillis()
        ));

        // Complete all emitters for this session
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.error("Error completing emitter for session: {}", sessionId, e);
                }
            }
            sessionEmitters.remove(sessionId);
        }
    }

    /**
     * Publish an error event and complete all emitters with error.
     */
    public void publishError(String sessionId, String message, String phase) {
        publishEvent(sessionId, new AgentEvent.Error(
            message,
            phase,
            System.currentTimeMillis()
        ));

        // Complete all emitters with error
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.completeWithError(new RuntimeException(message));
                } catch (Exception e) {
                    log.error("Error completing emitter with error for session: {}", sessionId, e);
                }
            }
            sessionEmitters.remove(sessionId);
        }
    }

    /**
     * Publish a fix suggestion event.
     */
    public void publishFixSuggestion(String sessionId, String componentName, String testType,
                                      Map<String, Object> violations, String suggestedFix,
                                      String explanation, String filePath, int severity) {
        publishEvent(sessionId, new AgentEvent.FixSuggestion(
            componentName,
            testType,
            violations,
            suggestedFix,
            explanation,
            filePath,
            severity,
            System.currentTimeMillis()
        ));
    }

    // --- New Workflow Event Publishers ---

    /**
     * Publish a progress event with step numbers.
     */
    public void publishProgress(String sessionId, String message, String phase, int currentStep, int totalSteps) {
        publishEvent(sessionId, new AgentEvent.Progress(
            message,
            phase,
            currentStep,
            totalSteps,
            System.currentTimeMillis()
        ));
    }

    /**
     * Publish a workflow started event.
     */
    public void publishWorkflowStarted(String sessionId, String message, int totalComponents, List<String> tests) {
        publishEvent(sessionId, new AgentEvent.WorkflowStarted(
            message,
            totalComponents,
            tests,
            System.currentTimeMillis()
        ));
    }

    /**
     * Publish a workflow component result event.
     */
    public void publishWorkflowComponentResult(String sessionId, WorkflowResult.ComponentTestResult result) {
        publishEvent(sessionId, new AgentEvent.WorkflowComponentResult(
            result,
            System.currentTimeMillis()
        ));
    }

    /**
     * Publish a workflow summary event.
     */
    public void publishWorkflowSummary(String sessionId, WorkflowResult.WorkflowSummary summary) {
        publishEvent(sessionId, new AgentEvent.WorkflowSummaryEvent(
            summary,
            System.currentTimeMillis()
        ));
    }

    /**
     * Publish a workflow done event and complete all emitters.
     */
    public void publishWorkflowDone(String sessionId, String message) {
        publishEvent(sessionId, new AgentEvent.WorkflowDone(
            message,
            System.currentTimeMillis()
        ));

        // Complete all emitters for this session
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.error("Error completing emitter for session: {}", sessionId, e);
                }
            }
            sessionEmitters.remove(sessionId);
        }
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
     * Get number of active sessions.
     */
    public int getActiveSessionCount() {
        return sessionEmitters.size();
    }

    /**
     * Get number of emitters for a session.
     */
    public int getEmitterCount(String sessionId) {
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.get(sessionId);
        return emitters != null ? emitters.size() : 0;
    }
}
