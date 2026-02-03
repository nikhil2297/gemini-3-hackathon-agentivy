package com.agentivy.backend.tools;

import com.agentivy.backend.tools.registry.ToolCategory;
import com.agentivy.backend.tools.registry.ToolMetadata;
import com.agentivy.backend.tools.registry.ToolProvider;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableMap;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import io.reactivex.rxjava3.core.Maybe;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Playwright Tools for Browser-Based Accessibility Testing
 *
 * Provides Axe-core WCAG accessibility auditing for Angular components.
 *
 * WORKFLOW:
 * 1. ComponentScaffoldTool.loadComponentForTesting() → creates harness, returns fullTestUrl + testWrapperSelector
 * 2. Start Angular dev server (ng serve)
 * 3. PlaywrightTools.runAccessibilityAudit(fullTestUrl, testWrapperSelector, "AA")
 */
@Slf4j
@Component
public class PlaywrightTools implements ToolProvider {

    @Value("${agentivy.playwright.enabled:true}")
    private boolean playwrightEnabled;

    @Value("${agentivy.playwright.timeout-ms:30000}")
    private int timeoutMs;

    @Value("${agentivy.playwright.headless:true}")
    private boolean headless;

    private Playwright playwright;
    private Browser browser;

    // Axe-core CDN for accessibility testing
    private static final String AXE_CORE_CDN = "https://cdnjs.cloudflare.com/ajax/libs/axe-core/4.8.4/axe.min.js";

    // ==================== LIFECYCLE ====================

    @PostConstruct
    public void init() {
        if (!playwrightEnabled) {
            log.info("Playwright is disabled via configuration");
            return;
        }

        try {
            log.info("Initializing Playwright browser (headless: {})", headless);
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setTimeout(timeoutMs));
            log.info("Playwright browser initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Playwright: {}", e.getMessage());
            playwrightEnabled = false;
        }
    }

    @PreDestroy
    public void cleanup() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    // ==================== ADK TOOLS ====================

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "playwright.accessibility",
            "Accessibility Auditing",
            "WCAG accessibility auditing using Axe-core and Playwright browser automation",
            ToolCategory.ACCESSIBILITY,
            "1.0.0",
            playwrightEnabled,
            List.of("accessibility", "wcag", "a11y", "axe-core", "playwright"),
            Map.of(
                "timeout-ms", timeoutMs,
                "headless", headless,
                "axe-core-version", "4.8.4"
            )
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(
                FunctionTool.create(this, "runAccessibilityAudit")
        );
    }

    // ==================== ACCESSIBILITY AUDIT ====================

    /**
     * Runs a WCAG accessibility audit using Axe-core.
     *
     * WHEN TO USE:
     * - After creating a test harness with ComponentScaffoldTool
     * - To identify WCAG violations in a component
     * - To verify accessibility fixes were applied
     *
     * PREREQUISITES:
     * - Angular dev server running (ng serve)
     * - Test harness created via loadComponentForTesting()
     *
     * EXAMPLE:
     * After loadComponentForTesting returns:
     *   { fullTestUrl: "http://localhost:4200/agent-ivy-harness", testWrapperSelector: "#test-wrapper-123" }
     *
     * Call: runAccessibilityAudit(
     *   componentUrl: "http://localhost:4200/agent-ivy-harness",
     *   componentSelector: "#test-wrapper-123",
     *   wcagLevel: "AA"
     * )
     */
    public Maybe<ImmutableMap<String, Object>> runAccessibilityAudit(
            @Schema(
                    name = "componentUrl",
                    description = "Full URL where the component is accessible. " +
                            "Use 'fullTestUrl' from loadComponentForTesting output. " +
                            "Example: 'http://localhost:4200/agent-ivy-harness'"
            )
            String componentUrl,

            @Schema(
                    name = "componentSelector",
                    description = "CSS selector to scope the audit. " +
                            "Use 'testWrapperSelector' from loadComponentForTesting output. " +
                            "Example: '#test-wrapper-1704567890123'"
            )
            String componentSelector,

            @Schema(
                    name = "wcagLevel",
                    description = "WCAG conformance level: 'A' (minimum), 'AA' (recommended), 'AAA' (strictest). " +
                            "Default: 'AA'"
            )
            String wcagLevel) {

        return Maybe.fromCallable(() -> {
            if (!playwrightEnabled || browser == null) {
                return ImmutableMap.<String, Object>of(
                        "status", "error",
                        "message", "Playwright not initialized. Run: ./gradlew exec -PmainClass=com.microsoft.playwright.CLI -Pargs=\"install chromium\"",
                        "passed", false
                );
            }

            log.info("Running accessibility audit: url={}, selector={}, level={}",
                    componentUrl, componentSelector, wcagLevel);

            String level = (wcagLevel != null && !wcagLevel.isBlank()) ? wcagLevel.toUpperCase() : "AA";

            try (BrowserContext context = browser.newContext();
                 Page page = context.newPage()) {

                // Navigate to component
                // Use DOMCONTENTLOADED for even faster testing (doesn't wait for all resources)
                page.navigate(componentUrl, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(timeoutMs));

                log.info("Page loaded, running accessibility audit...");

                // Wait for component to render (with timeout)
                try {
                    page.waitForSelector(componentSelector, new Page.WaitForSelectorOptions()
                            .setTimeout(15000));

                    log.info("Component selector '{}' found, proceeding with audit", componentSelector);
                } catch (Exception e) {
                    log.warn("Component selector not found within 15s, proceeding anyway");
                    // Continue with test even if selector not found
                }

                // Inject Axe-core library
                page.addScriptTag(new Page.AddScriptTagOptions().setUrl(AXE_CORE_CDN));

                log.info("Axe-core library injected, waiting for it to load...");

                page.waitForFunction("typeof axe !== 'undefined'");

                log.info("Axe-core loaded, executing audit...");

                // Run Axe audit
                String axeScript = buildAxeScript(componentSelector, level);

                log.info("Axe script constructed, evaluating...");

                // For extremely heavy pages, skip the full Axe audit and return a warning
                // This is a pragmatic approach for components with 10,000+ DOM elements
                log.warn("Component may be too complex for full Axe audit - attempting quick validation");

                @SuppressWarnings("unchecked")
                Map<String, Object> axeResults;
                try {
                    log.info("Starting Axe evaluation (this may timeout on heavy pages)...");

                    // Use a thread pool to run evaluate with hard timeout
                    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                    var future = executor.submit(() -> {
                        try {
                            return (Map<String, Object>) page.evaluate(axeScript);
                        } catch (Exception e) {
                            throw new RuntimeException("Evaluate failed: " + e.getMessage(), e);
                        }
                    });

                    try {
                        axeResults = future.get(20, java.util.concurrent.TimeUnit.SECONDS);
                        log.info("Axe audit completed, processing results...");
                    } catch (java.util.concurrent.TimeoutException e) {
                        future.cancel(true);
                        log.error("Axe evaluation timed out after 20s");
                        throw new RuntimeException("Axe evaluation timed out after 20 seconds");
                    } finally {
                        executor.shutdownNow();
                    }
                } catch (Exception e) {
                    log.error("Axe evaluation failed or timed out, returning error result", e);
                    // Return a minimal error result if evaluation fails
                    return ImmutableMap.<String, Object>of(
                        "status", "error",
                        "message", "Axe evaluation timed out - page is too complex (10,000+ DOM elements): " + e.getMessage(),
                        "passed", false,
                        "componentUrl", componentUrl,
                        "violationCount", 0,
                        "violations", List.of()
                    );
                }

                return buildResponse(axeResults, componentUrl, componentSelector, level);

            } catch (Exception e) {
                log.error("Accessibility audit failed", e);
                return ImmutableMap.<String, Object>of(
                        "status", "error",
                        "message", "Audit failed: " + e.getMessage(),
                        "passed", false
                );
            }
        });
    }

    // ==================== HELPERS ====================

    private String buildAxeScript(String selector, String level) {
        String tags = switch (level) {
            case "A" -> "['wcag2a', 'wcag21a']";
            case "AAA" -> "['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag2aaa', 'wcag21aaa']";
            default -> "['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']"; // AA
        };

        return """
            async () => {
                const results = await axe.run('%s', {
                    runOnly: { type: 'tag', values: %s },
                    resultTypes: ['violations'],  // Only check violations, skip passes/incomplete
                    timeout: 50000,  // 50 second timeout for axe itself
                    performanceTimer: true  // Enable performance optimization
                });
                return {
                    violations: results.violations || [],
                    passes: [],
                    incomplete: []
                };
            }
            """.formatted(selector, tags);
    }

    @SuppressWarnings("unchecked")
    private ImmutableMap<String, Object> buildResponse(
            Map<String, Object> axeResults, String url, String selector, String level) {

        List<Map<String, Object>> violations = (List<Map<String, Object>>)
                axeResults.getOrDefault("violations", List.of());
        List<Map<String, Object>> passes = (List<Map<String, Object>>)
                axeResults.getOrDefault("passes", List.of());

        // Process violations for cleaner output
        List<Map<String, Object>> processedViolations = violations.stream()
                .map(v -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> nodes = (List<Map<String, Object>>) v.get("nodes");
                    return Map.<String, Object>of(
                            "id", v.get("id"),
                            "impact", v.getOrDefault("impact", "unknown"),
                            "description", v.get("description"),
                            "help", v.get("help"),
                            "helpUrl", v.get("helpUrl"),
                            "nodeCount", nodes != null ? nodes.size() : 0,
                            "affectedNodes", nodes != null ? nodes.stream()
                                    .limit(5)
                                    .map(n -> n.get("html"))
                                    .toList() : List.of()
                    );
                })
                .toList();

        return ImmutableMap.<String, Object>builder()
                .put("status", "success")
                .put("passed", violations.isEmpty())
                .put("componentUrl", url)
                .put("componentSelector", selector)
                .put("wcagLevel", level)
                .put("violationCount", violations.size())
                .put("passCount", passes.size())
                .put("violations", processedViolations)
                .build();
    }
}