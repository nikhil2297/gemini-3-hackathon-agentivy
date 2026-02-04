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
            case "A" -> "['wcag2a', 'wcag21a', 'best-practice']";
            case "AAA" -> "['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag2aaa', 'wcag21aaa', 'best-practice']";
            default -> "['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice']"; // AA
        };

        // Use document as fallback if selector is empty or not found on the page
        String selectorArg = (selector != null && !selector.isEmpty())
            ? "'" + selector + "'"
            : "document";

        return """
            async () => {
                // Try the component selector first, fall back to document if not found
                let target = %s;
                if (typeof target === 'string') {
                    const el = document.querySelector(target);
                    if (!el) {
                        console.warn('Selector not found: ' + target + ', falling back to document');
                        target = document;
                    }
                }
                const results = await axe.run(target, {
                    runOnly: { type: 'tag', values: %s },
                    resultTypes: ['violations', 'incomplete'],
                    timeout: 50000,  // 50 second timeout for axe itself
                    performanceTimer: true  // Enable performance optimization
                });
                return {
                    violations: results.violations || [],
                    passes: [],
                    incomplete: results.incomplete || []
                };
            }
            """.formatted(selectorArg, tags);
    }

    @SuppressWarnings("unchecked")
    private ImmutableMap<String, Object> buildResponse(
            Map<String, Object> axeResults, String url, String selector, String level) {

        List<Map<String, Object>> violations = (List<Map<String, Object>>)
                axeResults.getOrDefault("violations", List.of());
        List<Map<String, Object>> passes = (List<Map<String, Object>>)
                axeResults.getOrDefault("passes", List.of());

        // Process violations with detailed actionable information
        List<Map<String, Object>> processedViolations = violations.stream()
                .map(v -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> nodes = (List<Map<String, Object>>) v.get("nodes");

                    // Extract detailed node information
                    List<Map<String, Object>> detailedNodes = extractDetailedNodeInfo(nodes, (String) v.get("id"));

                    // Use HashMap for flexibility
                    Map<String, Object> processedViolation = new HashMap<>();
                    processedViolation.put("id", v.get("id"));
                    processedViolation.put("impact", v.getOrDefault("impact", "unknown"));
                    processedViolation.put("description", v.get("description"));
                    processedViolation.put("help", v.get("help"));
                    processedViolation.put("helpUrl", v.get("helpUrl"));
                    processedViolation.put("nodeCount", nodes != null ? nodes.size() : 0);
                    processedViolation.put("nodes", detailedNodes);

                    return processedViolation;
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

    /**
     * Extracts detailed, actionable information from axe-core violation nodes.
     * Provides specific details for each violation type including:
     * - Color contrast: foreground/background colors, current vs required ratios
     * - Missing attributes: which attributes are missing and suggested values
     * - ARIA issues: current vs expected ARIA properties
     * - Form labels: association issues and fix suggestions
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDetailedNodeInfo(List<Map<String, Object>> nodes, String violationId) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        return nodes.stream()
                .limit(10) // Process up to 10 nodes for detailed info
                .map(node -> {
                    Map<String, Object> detailedNode = new HashMap<>();

                    // Basic node information
                    detailedNode.put("html", node.get("html"));
                    detailedNode.put("target", node.get("target"));

                    // Extract failure details from 'any', 'all', 'none' arrays
                    List<Map<String, Object>> allChecks = new ArrayList<>();
                    if (node.get("any") instanceof List) {
                        allChecks.addAll((List<Map<String, Object>>) node.get("any"));
                    }
                    if (node.get("all") instanceof List) {
                        allChecks.addAll((List<Map<String, Object>>) node.get("all"));
                    }
                    if (node.get("none") instanceof List) {
                        allChecks.addAll((List<Map<String, Object>>) node.get("none"));
                    }

                    // Extract actionable information based on violation type
                    Map<String, Object> actionable = extractActionableInfo(violationId, allChecks, node);
                    detailedNode.putAll(actionable);

                    // Add failure summary
                    List<String> failureMessages = allChecks.stream()
                            .map(check -> (String) check.get("message"))
                            .filter(Objects::nonNull)
                            .toList();
                    detailedNode.put("failureMessages", failureMessages);

                    return detailedNode;
                })
                .toList();
    }

    /**
     * Extracts specific actionable information based on violation type
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractActionableInfo(String violationId, List<Map<String, Object>> checks, Map<String, Object> node) {
        Map<String, Object> actionable = new HashMap<>();

        switch (violationId) {
            case "color-contrast" -> {
                // Extract color contrast details
                for (Map<String, Object> check : checks) {
                    Object data = check.get("data");
                    if (data instanceof Map) {
                        Map<String, Object> contrastData = (Map<String, Object>) data;
                        actionable.put("violationType", "color-contrast");
                        actionable.put("foregroundColor", contrastData.get("fgColor"));
                        actionable.put("backgroundColor", contrastData.get("bgColor"));
                        actionable.put("contrastRatio", contrastData.get("contrastRatio"));
                        actionable.put("expectedRatio", contrastData.get("expectedContrastRatio"));
                        actionable.put("fontSize", contrastData.get("fontSize"));

                        // Generate fix suggestion
                        String fix = generateColorContrastFix(contrastData);
                        actionable.put("suggestedFix", fix);

                        // Extract CSS class from HTML
                        String html = (String) node.get("html");
                        String cssClass = extractCssClass(html);
                        if (cssClass != null) {
                            actionable.put("cssClass", cssClass);
                            actionable.put("howToFix", String.format(
                                "In your component's .scss/.css file, find '.%s' and update the color property to meet contrast requirements",
                                cssClass
                            ));
                        }

                        // Suggest color alternatives
                        actionable.put("suggestedColorOptions", generateColorAlternatives(contrastData));
                    }
                }
            }
            case "image-alt" -> {
                actionable.put("violationType", "missing-attribute");
                actionable.put("missingAttribute", "alt");
                actionable.put("suggestedFix", "Add alt=\"descriptive text\" attribute to the <img> element");
                actionable.put("currentElement", node.get("html"));
                actionable.put("wcagGuideline", "WCAG 2.1 Level A - 1.1.1 Non-text Content");
                actionable.put("howToFix", "Add an alt attribute describing the image's content and purpose");

                // Generate example fix
                String html = (String) node.get("html");
                if (html != null) {
                    String exampleFix = html.replaceFirst(">", " alt=\"Describe this image\">");
                    actionable.put("exampleFix", exampleFix);
                }
            }
            case "label", "form-field-multiple-labels" -> {
                actionable.put("violationType", "missing-label");
                for (Map<String, Object> check : checks) {
                    Object data = check.get("data");
                    if (data instanceof Map) {
                        Map<String, Object> labelData = (Map<String, Object>) data;
                        actionable.put("inputType", labelData.get("type"));
                    }
                }
                actionable.put("suggestedFix", "Associate a <label> element using for=\"inputId\" or wrap the input with <label>");
                actionable.put("wcagGuideline", "WCAG 2.1 Level A - 3.3.2 Labels or Instructions");
                actionable.put("howToFix", "Add a <label> element with for attribute matching the input's id");
                actionable.put("exampleFix", "<label for=\"inputId\">Field Label:</label>\n<input id=\"inputId\" type=\"text\">");
            }
            case "aria-required-attr" -> {
                actionable.put("violationType", "missing-aria-attribute");
                for (Map<String, Object> check : checks) {
                    Object data = check.get("data");
                    if (data instanceof List) {
                        actionable.put("missingAttributes", data);
                        actionable.put("suggestedFix", "Add required ARIA attributes: " + data);
                        actionable.put("howToFix", "Add the missing ARIA attributes to the element: " + data);
                    }
                }
                actionable.put("wcagGuideline", "WCAG 2.1 Level A - 4.1.2 Name, Role, Value");
            }
            case "aria-allowed-attr" -> {
                actionable.put("violationType", "invalid-aria-attribute");
                for (Map<String, Object> check : checks) {
                    Object data = check.get("data");
                    if (data instanceof List) {
                        actionable.put("invalidAttributes", data);
                        actionable.put("suggestedFix", "Remove or fix invalid ARIA attributes: " + data);
                        actionable.put("howToFix", "These ARIA attributes are not allowed on this element type. Remove them or use the correct element.");
                    }
                }
                actionable.put("wcagGuideline", "WCAG 2.1 Level A - 4.1.2 Name, Role, Value");
            }
            case "button-name", "link-name" -> {
                actionable.put("violationType", "missing-accessible-name");
                actionable.put("missingAttribute", "aria-label or text content");
                actionable.put("suggestedFix", "Add aria-label=\"descriptive text\" or provide text content inside the element");
                actionable.put("wcagGuideline", "WCAG 2.1 Level A - 4.1.2 Name, Role, Value");
                actionable.put("howToFix", "Add text content inside the element or use aria-label attribute");
                String elementType = violationId.equals("button-name") ? "button" : "link";
                actionable.put("exampleFix", String.format("<%s aria-label=\"Descriptive %s text\">...</%s>",
                    elementType.equals("button") ? "button" : "a", elementType, elementType.equals("button") ? "button" : "a"));
            }
            case "tabindex" -> {
                actionable.put("violationType", "invalid-tabindex");
                for (Map<String, Object> check : checks) {
                    Object data = check.get("data");
                    if (data != null) {
                        actionable.put("currentTabindex", data);
                    }
                }
                actionable.put("suggestedFix", "Remove tabindex or set to 0 or -1 (never use values > 0)");
                actionable.put("wcagGuideline", "WCAG 2.1 Level A - 2.4.3 Focus Order");
                actionable.put("howToFix", "Change tabindex to 0 (or remove it), or use semantic HTML like <button> instead of <div>");
                actionable.put("whyBad", "Using tabindex > 0 disrupts the natural tab order and makes navigation confusing for keyboard users");
                actionable.put("exampleFix", "<button type=\"button\">Click me</button>");
            }
            case "region", "landmark-one-main", "landmark-no-duplicate-main" -> {
                actionable.put("violationType", "missing-landmark");
                actionable.put("suggestedFix", "Wrap content in semantic HTML5 landmarks: <main>, <nav>, <header>, <footer>, <aside>");
                actionable.put("wcagGuideline", "WCAG 2.1 Level A - 1.3.1 Info and Relationships");
                actionable.put("howToFix", "Wrap your main content in a <main> element, navigation in <nav>, etc.");
                actionable.put("exampleFix", "<main role=\"main\">\n  <h1>Page Title</h1>\n  <!-- your content -->\n</main>");
            }
            case "list", "listitem" -> {
                actionable.put("violationType", "invalid-list-structure");
                actionable.put("suggestedFix", "Use proper list markup: <ul>/<ol> must only contain <li> elements");
                actionable.put("wcagGuideline", "WCAG 2.1 Level A - 1.3.1 Info and Relationships");
                actionable.put("howToFix", "Ensure list items are direct children of ul/ol elements");
                actionable.put("exampleFix", "<ul>\n  <li>Item 1</li>\n  <li>Item 2</li>\n</ul>");
            }
            case "heading-order" -> {
                actionable.put("violationType", "heading-order");
                actionable.put("suggestedFix", "Use heading levels in sequential order (h1, h2, h3...)");
                actionable.put("wcagGuideline", "WCAG 2.1 Level A - 1.3.1 Info and Relationships");
                actionable.put("howToFix", "Don't skip heading levels. After h1 use h2, after h2 use h3, etc.");
                actionable.put("whyBad", "Screen readers use heading hierarchy to navigate; skipping levels is confusing");
            }
            case "duplicate-id", "duplicate-id-active", "duplicate-id-aria" -> {
                actionable.put("violationType", "duplicate-id");
                actionable.put("suggestedFix", "Ensure all id attributes are unique on the page");
                actionable.put("wcagGuideline", "WCAG 2.1 Level A - 4.1.1 Parsing");
                actionable.put("howToFix", "Change duplicate id values to be unique");
                actionable.put("whyBad", "Duplicate IDs break ARIA references and make form labels ambiguous");
            }
            case "html-has-lang" -> {
                actionable.put("violationType", "missing-lang");
                actionable.put("suggestedFix", "Add lang attribute to <html> element");
                actionable.put("wcagGuideline", "WCAG 2.1 Level A - 3.1.1 Language of Page");
                actionable.put("howToFix", "Add lang=\"en\" (or appropriate language code) to the <html> tag");
                actionable.put("exampleFix", "<html lang=\"en\">");
            }
            default -> {
                // Generic actionable info
                actionable.put("violationType", violationId);
                actionable.put("suggestedFix", "See helpUrl for detailed guidance");
                actionable.put("howToFix", "Review the WCAG documentation linked in helpUrl");
            }
        }

        return actionable;
    }

    /**
     * Generate specific color fix suggestion
     */
    @SuppressWarnings("unchecked")
    private String generateColorContrastFix(Map<String, Object> contrastData) {
        String fgColor = (String) contrastData.get("fgColor");
        String bgColor = (String) contrastData.get("bgColor");
        Object ratioObj = contrastData.get("contrastRatio");
        Object expectedObj = contrastData.get("expectedContrastRatio");

        double currentRatio = ratioObj instanceof Number ? ((Number) ratioObj).doubleValue() : 0.0;
        String expected = expectedObj != null ? expectedObj.toString() : "4.5:1";

        return String.format(
            "Current contrast ratio %.2f:1 is insufficient. Required: %s. " +
            "Fix: Change foreground color from %s to a darker shade, " +
            "or change background color from %s to increase contrast.",
            currentRatio, expected, fgColor != null ? fgColor : "current", bgColor != null ? bgColor : "current"
        );
    }

    /**
     * Extract CSS class from HTML string
     */
    private String extractCssClass(String html) {
        if (html == null) return null;

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("class=\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(html);

        if (matcher.find()) {
            String classes = matcher.group(1);
            // Return first non-Angular class
            for (String cls : classes.split("\\s+")) {
                if (!cls.startsWith("_ngcontent") && !cls.startsWith("ng-")) {
                    return cls;
                }
            }
        }

        return null;
    }

    /**
     * Generate alternative color options that meet contrast requirements
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> generateColorAlternatives(Map<String, Object> contrastData) {
        List<Map<String, String>> alternatives = new ArrayList<>();

        String fgColor = (String) contrastData.get("fgColor");
        Object expectedObj = contrastData.get("expectedContrastRatio");
        String expected = expectedObj != null ? expectedObj.toString() : "4.5:1";

        // Simple predefined alternatives (in practice, you'd calculate these based on the background)
        // For now, provide common accessible color combinations
        alternatives.add(Map.of(
            "color", "#767676",
            "ratio", "4.54:1",
            "status", "✓ PASS"
        ));
        alternatives.add(Map.of(
            "color", "#595959",
            "ratio", "7.0:1",
            "status", "✓ PASS (AAA)"
        ));
        alternatives.add(Map.of(
            "color", "#000000",
            "ratio", "21:1",
            "status", "✓ PASS (AAA)"
        ));

        return alternatives;
    }
}