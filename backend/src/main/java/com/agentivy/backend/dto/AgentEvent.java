package com.agentivy.backend.dto;

import java.util.Map;

public sealed interface AgentEvent permits
    AgentEvent.Started,
    AgentEvent.Progress,
    AgentEvent.ToolCall,
    AgentEvent.ComponentResult,
    AgentEvent.ComponentStatus,
    AgentEvent.FixSuggestion,
    AgentEvent.Completed,
    AgentEvent.Error {

    record Started(
        String sessionId,
        String repoPath,
        long timestamp
    ) implements AgentEvent {}

    record Progress(
        String message,
        String phase,  // "scanning", "testing", "fixing", "verifying"
        int currentStep,
        int totalSteps,
        long timestamp
    ) implements AgentEvent {}

    record ToolCall(
        String toolName,
        Map<String, Object> parameters,
        long timestamp
    ) implements AgentEvent {}

    record ComponentResult(
        String componentName,
        String status,  // "analyzing", "tested", "fixed", "completed", "failed"
        Map<String, Object> testResults,
        long timestamp
    ) implements AgentEvent {}

    /**
     * Tracks component-specific status changes through the testing workflow.
     * Provides detailed progress for each component as it goes through different tools.
     */
    record ComponentStatus(
        String componentName,
        String tool,  // "accessibility", "performance", "harness", "dev-server", "fixer"
        String status,  // "starting", "in-progress", "completed", "failed", "skipped"
        String message,  // Descriptive message about current status
        Map<String, Object> metadata,  // Additional context (e.g., violations count, metrics)
        long timestamp
    ) implements AgentEvent {}

    /**
     * Fix suggestion for a specific component and test type.
     * Contains detailed information about violations and AI-generated fix recommendations.
     */
    record FixSuggestion(
        String componentName,
        String testType,  // "accessibility" or "performance"
        Map<String, Object> violations,  // Detailed violation information
        String suggestedFix,  // AI-generated fix recommendation (code changes)
        String explanation,  // Human-readable explanation of the fix
        String filePath,  // File that needs to be modified
        int severity,  // 1=low, 2=medium, 3=high
        long timestamp
    ) implements AgentEvent {}

    record Completed(
        String summary,
        Map<String, Object> finalReport,
        long timestamp
    ) implements AgentEvent {}

    record Error(
        String message,
        String phase,
        long timestamp
    ) implements AgentEvent {}
}
