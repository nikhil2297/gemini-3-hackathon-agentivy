package com.agentivy.backend.tools.testing;

import com.agentivy.backend.service.EventPublisherHelper;
import com.agentivy.backend.service.SessionContext;
import com.agentivy.backend.tools.PlaywrightTools;
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

import java.util.*;

/**
 * Atomic tool for accessibility testing using Playwright + axe-core.
 * Tests components for WCAG compliance violations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessibilityTestingTool implements ToolProvider {

    private final PlaywrightTools playwrightTools;
    private final EventPublisherHelper eventPublisher;

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "testing.accessibility",
            "Accessibility Testing Tool",
            "Tests components for WCAG accessibility violations using Playwright + axe-core",
            ToolCategory.COMPONENT_TESTING,
            "1.0.0",
            true,
            List.of("accessibility", "wcag", "axe-core", "testing", "playwright"),
            Map.of(
                "standards", "WCAG 2.1 Level A, AA, AAA",
                "engine", "axe-core"
            )
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(FunctionTool.create(this, "runAccessibilityTest"));
    }

    /**
     * Runs accessibility tests on the specified URL.
     *
     * @param componentUrl The URL of the component to test (e.g., http://localhost:4200/agent-ivy-harness)
     * @param wcagLevel WCAG conformance level (A, AA, AAA) - defaults to AA
     * @return Structured accessibility test results with violations
     */
    public Maybe<ImmutableMap<String, Object>> runAccessibilityTest(
            @Schema(name = "componentUrl") String componentUrl,
            @Schema(name = "wcagLevel") String wcagLevel) {

        return Maybe.fromCallable(() -> {
            log.info("Running accessibility test on: {}", componentUrl);

            // Use current component from session context if available, otherwise extract from URL
            String componentName = SessionContext.getCurrentComponent() != null
                ? SessionContext.getCurrentComponent()
                : extractComponentNameFromUrl(componentUrl);

            // Publish tool call event
            eventPublisher.publishToolCall("runAccessibilityTest", "Running accessibility audit on " + componentUrl);

            // Default to WCAG AA if not specified
            String level = (wcagLevel == null || wcagLevel.isEmpty()) ? "AA" : wcagLevel.toUpperCase();

            // Publish component status: starting with detailed metadata
            long startTime = System.currentTimeMillis();
            eventPublisher.publishComponentStatus(
                componentName,
                "accessibility",
                "starting",
                "Initializing accessibility audit...",
                Map.of(
                    "wcagLevel", level,
                    "targetUrl", componentUrl,
                    "selector", "body",
                    "browserReady", true,
                    "axeVersion", "4.8.0"
                )
            );

            try {
                // Publish in-progress status
                eventPublisher.publishComponentStatus(
                    componentName,
                    "accessibility",
                    "in-progress",
                    "Running axe-core audit..."
                );

                // Use PlaywrightTools to run axe-core accessibility audit
                // Use body as selector to test entire component
                ImmutableMap<String, Object> auditResult = playwrightTools
                    .runAccessibilityAudit(componentUrl, "body", level)
                    .blockingGet();

                if ("error".equals(auditResult.get("status"))) {
                    return ImmutableMap.<String, Object>builder()
                        .put("status", "error")
                        .put("message", auditResult.get("message"))
                        .build();
                }

                // Extract and structure the violations
                List<Map<String, Object>> violations = (List<Map<String, Object>>) auditResult.get("violations");
                Integer violationCountObj = (Integer) auditResult.get("violationCount");
                int violationCount = violationCountObj != null ? violationCountObj : (violations != null ? violations.size() : 0);

                // Categorize violations by severity
                Map<String, Integer> severityCounts = categorizeBySeverity(violations);

                // Extract critical issues (high severity)
                List<Map<String, Object>> criticalIssues = extractCriticalIssues(violations);

                // Count violations by rule type
                Map<String, Integer> violationsByRule = countViolationsByRule(violations);

                // Count elements audited (estimate from violations)
                int elementsAudited = estimateElementsAudited(violations);

                // Calculate compliance score
                double complianceScore = calculateComplianceScore(violationCount, elementsAudited);

                long timeElapsed = System.currentTimeMillis() - startTime;

                log.info("✓ Accessibility test completed: {} violations found", violationCount);

                // Build detailed violation information for metadata
                List<Map<String, Object>> violationDetails = buildViolationDetails(criticalIssues);

                // Publish component status: completed with rich metadata
                eventPublisher.publishComponentStatus(
                    componentName,
                    "accessibility",
                    "completed",
                    violationCount > 0
                        ? String.format("Found %d violation(s) (%d critical, %d serious)",
                            violationCount,
                            severityCounts.getOrDefault("critical", 0),
                            severityCounts.getOrDefault("serious", 0))
                        : "All accessibility tests passed",
                    Map.of(
                        "passed", violationCount == 0,
                        "totalViolations", violationCount,
                        "wcagLevel", level,
                        "severityCounts", severityCounts,
                        "violationsByRule", violationsByRule,
                        "violationDetails", violationDetails,
                        "elementsAudited", elementsAudited,
                        "timeElapsed", timeElapsed,
                        "complianceScore", Math.round(complianceScore * 10) / 10.0
                    )
                );

                // Also publish result event for backward compatibility
                eventPublisher.publishComponentResult(
                    componentName,
                    violationCount > 0 ? "accessibility-issues" : "passed",
                    Map.of(
                        "totalViolations", violationCount,
                        "wcagLevel", level,
                        "severityCounts", severityCounts
                    )
                );

                return ImmutableMap.<String, Object>builder()
                    .put("status", "success")
                    .put("componentUrl", componentUrl)
                    .put("wcagLevel", level)
                    .put("timestamp", System.currentTimeMillis())
                    .put("totalViolations", violationCount)
                    .put("severityCounts", severityCounts)
                    .put("criticalIssues", criticalIssues)
                    .put("allViolations", violations)
                    .put("passed", violationCount == 0)
                    .build();

            } catch (Exception e) {
                log.error("Accessibility test failed", e);

                // Publish failed status
                eventPublisher.publishComponentStatus(
                    componentName,
                    "accessibility",
                    "failed",
                    "Accessibility test failed: " + e.getMessage()
                );

                return ImmutableMap.<String, Object>builder()
                    .put("status", "error")
                    .put("message", "Accessibility test failed: " + e.getMessage())
                    .build();
            }
        });
    }

    /**
     * Extract component name from URL.
     * Examples:
     * - http://localhost:4200/agent-ivy-harness → "agent-ivy-harness"
     * - http://localhost:4200/test-component → "test-component"
     */
    private String extractComponentNameFromUrl(String url) {
        try {
            String path = url.substring(url.lastIndexOf("/") + 1);
            return path.isEmpty() ? "unknown" : path;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Count violations by rule type.
     */
    private Map<String, Integer> countViolationsByRule(List<Map<String, Object>> violations) {
        if (violations == null || violations.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> counts = new java.util.HashMap<>();
        for (Map<String, Object> violation : violations) {
            String ruleId = (String) violation.get("id");
            if (ruleId != null) {
                counts.put(ruleId, counts.getOrDefault(ruleId, 0) + 1);
            }
        }
        return counts;
    }

    /**
     * Estimate number of elements audited from violations.
     */
    private int estimateElementsAudited(List<Map<String, Object>> violations) {
        if (violations == null || violations.isEmpty()) {
            return 50; // Default estimate
        }

        int totalNodes = 0;
        for (Map<String, Object> violation : violations) {
            Object nodesObj = violation.get("nodes");
            if (nodesObj instanceof List) {
                totalNodes += ((List<?>) nodesObj).size();
            }
        }
        // Estimate total elements as 10x the problematic ones
        return Math.max(totalNodes * 10, 50);
    }

    /**
     * Calculate compliance score based on violations.
     */
    private double calculateComplianceScore(int violationCount, int elementsAudited) {
        if (elementsAudited == 0) return 100.0;
        double issueRate = (double) violationCount / elementsAudited;
        return Math.max(0, 100.0 - (issueRate * 1000)); // Scale appropriately
    }

    /**
     * Build detailed violation information for metadata with actionable details.
     */
    private List<Map<String, Object>> buildViolationDetails(List<Map<String, Object>> criticalIssues) {
        if (criticalIssues == null || criticalIssues.isEmpty()) {
            return List.of();
        }

        return criticalIssues.stream()
            .limit(10) // Top 10 critical issues with full details
            .map(issue -> {
                Map<String, Object> details = new HashMap<>();
                details.put("id", issue.getOrDefault("id", "unknown"));
                details.put("impact", issue.getOrDefault("impact", "unknown"));
                details.put("description", issue.getOrDefault("description", ""));
                details.put("help", issue.getOrDefault("help", ""));
                details.put("helpUrl", issue.getOrDefault("helpUrl", ""));

                // Include detailed node information with actionable fixes
                if (issue.containsKey("nodes") && issue.get("nodes") instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> nodes = (List<Map<String, Object>>) issue.get("nodes");
                    details.put("nodeCount", nodes.size());

                    // Include first few nodes with their detailed actionable information
                    List<Map<String, Object>> detailedNodes = nodes.stream()
                        .limit(3)
                        .map(node -> {
                            Map<String, Object> nodeDetail = new HashMap<>();
                            nodeDetail.put("element", node.get("html"));
                            nodeDetail.put("target", node.get("target"));

                            // Include actionable details
                            if (node.containsKey("violationType")) {
                                nodeDetail.put("violationType", node.get("violationType"));
                            }
                            if (node.containsKey("suggestedFix")) {
                                nodeDetail.put("suggestedFix", node.get("suggestedFix"));
                            }
                            if (node.containsKey("howToFix")) {
                                nodeDetail.put("howToFix", node.get("howToFix"));
                            }
                            if (node.containsKey("exampleFix")) {
                                nodeDetail.put("exampleFix", node.get("exampleFix"));
                            }

                            // Color contrast specific details
                            if (node.containsKey("foregroundColor")) {
                                nodeDetail.put("foregroundColor", node.get("foregroundColor"));
                                nodeDetail.put("backgroundColor", node.get("backgroundColor"));
                                nodeDetail.put("contrastRatio", node.get("contrastRatio"));
                                nodeDetail.put("expectedRatio", node.get("expectedRatio"));
                            }

                            // Missing attributes
                            if (node.containsKey("missingAttribute")) {
                                nodeDetail.put("missingAttribute", node.get("missingAttribute"));
                            }
                            if (node.containsKey("missingAttributes")) {
                                nodeDetail.put("missingAttributes", node.get("missingAttributes"));
                            }

                            // Include failure messages
                            if (node.containsKey("failureMessages")) {
                                nodeDetail.put("failureMessages", node.get("failureMessages"));
                            }

                            return nodeDetail;
                        })
                        .toList();

                    details.put("detailedNodes", detailedNodes);
                } else {
                    details.put("nodeCount", 0);
                    details.put("detailedNodes", List.of());
                }

                return details;
            })
            .toList();
    }

    /**
     * Categorizes violations by severity level.
     */
    private Map<String, Integer> categorizeBySeverity(List<Map<String, Object>> violations) {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("critical", 0);
        counts.put("serious", 0);
        counts.put("moderate", 0);
        counts.put("minor", 0);

        if (violations == null) {
            return counts;
        }

        for (Map<String, Object> violation : violations) {
            String impact = (String) violation.get("impact");
            if (impact != null) {
                counts.merge(impact.toLowerCase(), 1, Integer::sum);
            }
        }

        return counts;
    }

    /**
     * Extracts critical and serious violations for quick review with full node details.
     */
    private List<Map<String, Object>> extractCriticalIssues(List<Map<String, Object>> violations) {
        if (violations == null) {
            return List.of();
        }

        return violations.stream()
            .filter(v -> {
                String impact = (String) v.get("impact");
                return "critical".equalsIgnoreCase(impact) || "serious".equalsIgnoreCase(impact);
            })
            .map(v -> {
                Map<String, Object> issue = new HashMap<>();
                issue.put("id", v.get("id"));
                issue.put("impact", v.get("impact"));
                issue.put("description", v.get("description"));
                issue.put("help", v.get("help"));
                issue.put("helpUrl", v.get("helpUrl"));

                // Include nodes with their detailed actionable information
                if (v.containsKey("nodes")) {
                    issue.put("nodes", v.get("nodes"));
                }

                return issue;
            })
            .toList();
    }
}
