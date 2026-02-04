package com.agentivy.backend.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO structures for the enhanced SSE workflow results.
 * Contains all nested record types for component-result and summary event payloads.
 */
public final class WorkflowResult {

    private WorkflowResult() {
        // Container for nested records, no instantiation
    }

    // --- Per-component structures ---

    public record ComponentInfo(
        String name,
        String relativePath,
        String fullName,          // e.g., "admin/settings/SettingsComponent"
        String tsPath,
        String htmlPath,
        String stylesPath
    ) {}

    public record ViolationCounts(
        int critical,
        int serious,
        int moderate,
        int minor,
        int total
    ) {}

    public record PassThreshold(
        int minScore,
        int maxCritical,
        int maxSerious
    ) {
        public static PassThreshold defaultThreshold() {
            return new PassThreshold(70, 0, 2);
        }
    }

    public record IssueDetail(
        String id,
        String severity,
        int line,            // 0 if unknown
        String element,
        String suggestion,
        Map<String, Object> actionableDetails  // Contains detailed fix information
    ) {
        // Convenience constructor for backward compatibility
        public IssueDetail(String id, String severity, int line, String element, String suggestion) {
            this(id, severity, line, element, suggestion, Map.of());
        }
    }

    public record FileIssues(
        String file,
        List<IssueDetail> issues
    ) {}

    public record TestResult(
        String status,            // "pass", "warning", "fail"
        int score,
        ViolationCounts violations,
        PassThreshold passThreshold,
        List<FileIssues> details
    ) {}

    public record ComponentTestResult(
        ComponentInfo component,
        TestResult accessibility,    // nullable if not tested
        TestResult performance       // nullable if not tested
    ) {}

    // --- Summary structures ---

    public record TestSummary(
        String status,
        int averageScore,
        int passed,
        int warned,
        int failed
    ) {}

    public record ScoreStatus(
        int score,
        String status
    ) {}

    public record ComponentScoreEntry(
        String fullName,
        ScoreStatus accessibility,   // nullable if not tested
        ScoreStatus performance      // nullable if not tested
    ) {}

    public record Summary(
        int totalComponents,
        String overallStatus,
        int overallScore,
        Map<String, TestSummary> testResults,       // keyed by "accessibility", "performance"
        List<ComponentScoreEntry> componentScores
    ) {}

    public record WorkflowSummary(
        String repoId,
        Summary summary
    ) {}
}
