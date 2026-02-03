package com.agentivy.backend.tools.fixing;

import com.agentivy.backend.tools.registry.ToolCategory;
import com.agentivy.backend.tools.registry.ToolMetadata;
import com.agentivy.backend.tools.registry.ToolProvider;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Atomic tool for automatically fixing accessibility violations in Angular components.
 * Uses LLM to generate fixes for WCAG violations found by accessibility testing.
 */
@Slf4j
@Component
public class AccessibilityFixerTool implements ToolProvider {

    private final LlmAgent accessibilityFixerAgent;
    private final InMemoryRunner runner;

    public AccessibilityFixerTool(
            @Qualifier("accessibilityFixerAgent") LlmAgent accessibilityFixerAgent,
            @Qualifier("accessibilityFixerRunner") InMemoryRunner runner) {
        this.accessibilityFixerAgent = accessibilityFixerAgent;
        this.runner = runner;
    }

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "fixing.accessibility",
            "Accessibility Fixer Tool",
            "Automatically fixes WCAG accessibility violations using LLM-powered code generation",
            ToolCategory.CODE_FIXING,
            "1.0.0",
            true,
            List.of("accessibility", "wcag", "fixing", "a11y", "llm"),
            Map.of(
                "capabilities", "Fixes missing ARIA labels, color contrast, semantic HTML, keyboard navigation",
                "wcagLevels", "A, AA, AAA"
            )
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(FunctionTool.create(this, "fixAccessibilityViolations"));
    }

    /**
     * Fixes accessibility violations in a component.
     *
     * @param repoPath Path to the Angular repository
     * @param componentClassName Name of the component class (e.g., "PerformanceIssueComponent")
     * @param violations JSON string containing accessibility violations from AccessibilityTestingTool
     * @return Result with applied fixes and file changes
     */
    public Maybe<ImmutableMap<String, Object>> fixAccessibilityViolations(
            @Schema(name = "repoPath") String repoPath,
            @Schema(name = "componentClassName") String componentClassName,
            @Schema(name = "violations") String violations) {

        return Maybe.fromCallable(() -> {
            log.info("Fixing accessibility violations for component: {}", componentClassName);

            try {
                // Find component files
                Path repoRoot = Path.of(repoPath);
                Path srcPath = repoRoot.resolve("src");

                Optional<Path> tsFile = findComponentFile(srcPath, componentClassName, ".component.ts");
                Optional<Path> htmlFile = findComponentFile(srcPath, componentClassName, ".component.html");

                if (tsFile.isEmpty() && htmlFile.isEmpty()) {
                    return ImmutableMap.<String, Object>builder()
                        .put("status", "error")
                        .put("message", "Component files not found for: " + componentClassName)
                        .build();
                }

                List<Map<String, String>> appliedFixes = new ArrayList<>();

                // Read current file contents
                String originalTs = tsFile.isPresent() ? Files.readString(tsFile.get()) : "";
                String originalHtml = htmlFile.isPresent() ? Files.readString(htmlFile.get()) : "";

                // Generate fixes using LLM
                log.info("Generating accessibility fixes using LLM...");
                Map<String, String> fixes = generateAccessibilityFixes(
                    componentClassName,
                    originalTs,
                    originalHtml,
                    violations
                );

                // Apply HTML fixes
                if (htmlFile.isPresent() && fixes.containsKey("html") && !fixes.get("html").equals(originalHtml)) {
                    String fixedHtml = fixes.get("html");
                    Files.writeString(htmlFile.get(), fixedHtml);
                    appliedFixes.add(Map.of(
                        "file", htmlFile.get().getFileName().toString(),
                        "type", "template",
                        "description", "Applied accessibility fixes to template"
                    ));
                    log.info("✓ Applied HTML fixes to: {}", htmlFile.get().getFileName());
                }

                // Apply TypeScript fixes
                if (tsFile.isPresent() && fixes.containsKey("typescript") && !fixes.get("typescript").equals(originalTs)) {
                    String fixedTs = fixes.get("typescript");
                    Files.writeString(tsFile.get(), fixedTs);
                    appliedFixes.add(Map.of(
                        "file", tsFile.get().getFileName().toString(),
                        "type", "component",
                        "description", "Applied accessibility fixes to component logic"
                    ));
                    log.info("✓ Applied TypeScript fixes to: {}", tsFile.get().getFileName());
                }

                if (appliedFixes.isEmpty()) {
                    return ImmutableMap.<String, Object>builder()
                        .put("status", "success")
                        .put("message", "No fixes needed - component is accessible")
                        .put("appliedFixes", appliedFixes)
                        .build();
                }

                return ImmutableMap.<String, Object>builder()
                    .put("status", "success")
                    .put("message", String.format("Applied %d accessibility fix(es)", appliedFixes.size()))
                    .put("appliedFixes", appliedFixes)
                    .put("explanation", fixes.getOrDefault("explanation", ""))
                    .build();

            } catch (Exception e) {
                log.error("Failed to fix accessibility violations", e);
                return ImmutableMap.<String, Object>builder()
                    .put("status", "error")
                    .put("message", "Fix generation failed: " + e.getMessage())
                    .build();
            }
        });
    }

    /**
     * Generate accessibility fixes using LLM.
     */
    private Map<String, String> generateAccessibilityFixes(
            String componentClassName,
            String tsCode,
            String htmlCode,
            String violations) {

        String prompt = buildAccessibilityFixPrompt(componentClassName, tsCode, htmlCode, violations);

        log.info("Sending accessibility fix request to LLM...");
        String response = generateCodeWithLLM(prompt);

        return parseFixResponse(response, tsCode, htmlCode);
    }

    /**
     * Build prompt for LLM to generate accessibility fixes.
     */
    private String buildAccessibilityFixPrompt(
            String componentClassName,
            String tsCode,
            String htmlCode,
            String violations) {

        return String.format("""
            You are an Angular accessibility expert. Fix the WCAG violations in this component.

            Component: %s

            TypeScript Code:
            ```typescript
            %s
            ```

            HTML Template:
            ```html
            %s
            ```

            Accessibility Violations:
            ```json
            %s
            ```

            INSTRUCTIONS:
            1. Fix all accessibility violations following WCAG 2.1 AA standards
            2. Common fixes include:
               - Add aria-label or aria-labelledby for interactive elements
               - Ensure sufficient color contrast (4.5:1 for normal text)
               - Add alt text for images
               - Use semantic HTML elements (button instead of div with click handler)
               - Ensure keyboard navigation (tabindex, focus management)
               - Add ARIA roles where appropriate
               - Fix form labels and associations
            3. Preserve all existing functionality
            4. Return the fixed code in this EXACT format:

            TYPESCRIPT_START
            [complete fixed TypeScript code here]
            TYPESCRIPT_END

            HTML_START
            [complete fixed HTML template here]
            HTML_END

            EXPLANATION_START
            [brief explanation of what was fixed]
            EXPLANATION_END

            IMPORTANT: Include ALL original code, not just the changes. Return complete files.
            """,
            componentClassName,
            tsCode.isEmpty() ? "// No TypeScript file" : tsCode,
            htmlCode.isEmpty() ? "<!-- No HTML template -->" : htmlCode,
            violations
        );
    }

    /**
     * Parse LLM response to extract fixed code.
     */
    private Map<String, String> parseFixResponse(String response, String originalTs, String originalHtml) {
        Map<String, String> result = new HashMap<>();

        // Extract TypeScript
        String tsPattern = "TYPESCRIPT_START\\s*(.+?)\\s*TYPESCRIPT_END";
        java.util.regex.Pattern tsPat = java.util.regex.Pattern.compile(tsPattern, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher tsMatcher = tsPat.matcher(response);
        if (tsMatcher.find()) {
            String fixedTs = tsMatcher.group(1).trim();
            // Remove markdown code fences if present
            fixedTs = fixedTs.replaceAll("^```typescript\\s*", "").replaceAll("^```\\s*", "").replaceAll("\\s*```$", "");
            result.put("typescript", fixedTs);
        } else {
            result.put("typescript", originalTs);
        }

        // Extract HTML
        String htmlPattern = "HTML_START\\s*(.+?)\\s*HTML_END";
        java.util.regex.Pattern htmlPat = java.util.regex.Pattern.compile(htmlPattern, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher htmlMatcher = htmlPat.matcher(response);
        if (htmlMatcher.find()) {
            String fixedHtml = htmlMatcher.group(1).trim();
            // Remove markdown code fences if present
            fixedHtml = fixedHtml.replaceAll("^```html\\s*", "").replaceAll("^```\\s*", "").replaceAll("\\s*```$", "");
            result.put("html", fixedHtml);
        } else {
            result.put("html", originalHtml);
        }

        // Extract explanation
        String explanationPattern = "EXPLANATION_START\\s*(.+?)\\s*EXPLANATION_END";
        java.util.regex.Pattern expPat = java.util.regex.Pattern.compile(explanationPattern, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher expMatcher = expPat.matcher(response);
        if (expMatcher.find()) {
            result.put("explanation", expMatcher.group(1).trim());
        }

        return result;
    }

    /**
     * Uses ADK runner to execute the LLM agent and generate fixes.
     */
    private String generateCodeWithLLM(String prompt) {
        try {
            // Create a session for this fix generation request
            Session session = runner.sessionService()
                .createSession(runner.appName(), "accessibility-fixer-" + System.currentTimeMillis())
                .blockingGet();

            // Create user message with the prompt
            Content userMsg = Content.fromParts(Part.fromText(prompt));

            // Run agent and collect final response
            AtomicReference<String> generatedCode = new AtomicReference<>("");

            Flowable<Event> events = runner.runAsync(
                session.userId(),
                session.id(),
                userMsg
            );

            // Process events and extract final response
            events.blockingForEach(event -> {
                if (event.finalResponse()) {
                    generatedCode.set(event.stringifyContent());
                }
            });

            return generatedCode.get();

        } catch (Exception e) {
            log.error("Failed to generate fixes with LLM", e);
            return "";
        }
    }

    /**
     * Find component file by class name and extension.
     */
    private Optional<Path> findComponentFile(Path srcPath, String className, String extension) {
        try (Stream<Path> stream = Files.walk(srcPath)) {
            Pattern classPattern = Pattern.compile("export\\s+class\\s+" + Pattern.quote(className) + "\\b");

            return stream
                .filter(p -> p.toString().endsWith(extension))
                .filter(p -> !p.toString().contains("node_modules"))
                .filter(p -> !p.toString().endsWith(".spec.ts"))
                .filter(p -> {
                    try {
                        // For HTML files, find the corresponding TS file to check class name
                        if (extension.equals(".component.html")) {
                            Path tsFile = Path.of(p.toString().replace(".component.html", ".component.ts"));
                            if (Files.exists(tsFile)) {
                                String tsContent = Files.readString(tsFile);
                                return classPattern.matcher(tsContent).find();
                            }
                            return false;
                        }

                        // For TS files, check directly
                        String content = Files.readString(p);
                        return classPattern.matcher(content).find();
                    } catch (Exception e) {
                        return false;
                    }
                })
                .findFirst();
        } catch (Exception e) {
            log.error("Error finding component file", e);
            return Optional.empty();
        }
    }

    /**
     * Suggest fixes for accessibility violations without applying them.
     * This method generates AI-powered fix recommendations but does not modify any files.
     *
     * @param repoPath Path to the Angular repository
     * @param componentClassName Name of the component class
     * @param tsPath Path to TypeScript file
     * @param htmlPath Path to HTML template file
     * @param violations List of accessibility violations
     * @return Suggested fixes with explanations
     */
    public Maybe<ImmutableMap<String, Object>> suggestFixes(
            String repoPath,
            String componentClassName,
            String tsPath,
            String htmlPath,
            String stylesPath,
            List<Map<String, Object>> violations) {

        return Maybe.fromCallable(() -> {
            log.info("Generating fix suggestions for component: {}", componentClassName);

            try {
                // Combine repoPath with relative paths to get absolute paths
                Path fullTsPath = tsPath != null ? Path.of(repoPath, tsPath) : null;
                Path fullHtmlPath = htmlPath != null ? Path.of(repoPath, htmlPath) : null;
                Path fullStylesPath = stylesPath != null ? Path.of(repoPath, stylesPath) : null;

                log.info("Reading TS file: {}", fullTsPath);
                log.info("Reading HTML file: {}", fullHtmlPath);
                log.info("Reading Styles file: {}", fullStylesPath);

                // Read component files using full absolute paths
                String tsContent = fullTsPath != null && Files.exists(fullTsPath)
                    ? Files.readString(fullTsPath)
                    : "";
                String htmlContent = fullHtmlPath != null && Files.exists(fullHtmlPath)
                    ? Files.readString(fullHtmlPath)
                    : "";
                String stylesContent = fullStylesPath != null && Files.exists(fullStylesPath)
                    ? Files.readString(fullStylesPath)
                    : "";

                // Log warning if files not found
                if (tsContent.isEmpty()) {
                    log.warn("Could not read TS file (file not found or empty): {}", fullTsPath);
                }
                if (htmlContent.isEmpty()) {
                    log.warn("Could not read HTML file (file not found or empty): {}", fullHtmlPath);
                }
                if (stylesContent.isEmpty()) {
                    log.warn("Could not read Styles file (file not found or empty): {}", fullStylesPath);
                }

                // Build prompt for AI to generate suggestions
                StringBuilder prompt = new StringBuilder();
                prompt.append("Analyze the following Angular component and suggest fixes for accessibility violations.\n\n");
                prompt.append("Component: ").append(componentClassName).append("\n\n");

                if (!tsContent.isEmpty()) {
                    prompt.append("TypeScript:\n```typescript\n")
                        .append(tsContent)
                        .append("\n```\n\n");
                }

                if (!htmlContent.isEmpty()) {
                    prompt.append("HTML Template:\n```html\n")
                        .append(htmlContent)
                        .append("\n```\n\n");
                }

                if (!stylesContent.isEmpty()) {
                    prompt.append("Styles (SCSS/CSS):\n```scss\n")
                        .append(stylesContent)
                        .append("\n```\n\n");
                }

                prompt.append("Accessibility Violations:\n");
                for (int i = 0; i < violations.size(); i++) {
                    Map<String, Object> violation = violations.get(i);
                    prompt.append(i + 1).append(". ")
                        .append("ID: ").append(violation.get("id")).append("\n")
                        .append("   Impact: ").append(violation.get("impact")).append("\n")
                        .append("   Description: ").append(violation.get("description")).append("\n")
                        .append("   Help: ").append(violation.get("help")).append("\n\n");
                }

                prompt.append("\nProvide:\n");
                prompt.append("1. A clear explanation of what changes are needed and why\n");
                prompt.append("2. The complete fixed code (HTML and/or TypeScript)\n");
                prompt.append("3. Specific line-by-line changes needed\n\n");
                prompt.append("Format your response as:\n");
                prompt.append("EXPLANATION:\n[Your explanation here]\n\n");
                prompt.append("SUGGESTED_CODE:\n```html\n[Fixed HTML code]\n```\n");

                // Use LLM to generate suggestions
                Session session = runner.sessionService()
                    .createSession(runner.appName(), "suggest-fixes-" + System.currentTimeMillis())
                    .blockingGet();

                Content userMessage = Content.fromParts(Part.fromText(prompt.toString()));
                AtomicReference<String> aiResponse = new AtomicReference<>("");

                Flowable<Event> eventStream = runner.runAsync(
                    session.userId(),
                    session.id(),
                    userMessage
                );

                eventStream.blockingForEach(event -> {
                    if (event.finalResponse()) {
                        aiResponse.set(event.stringifyContent());
                    }
                });

                // Parse AI response to extract explanation and suggested code
                String response = aiResponse.get();
                String explanation = extractSection(response, "EXPLANATION:", "SUGGESTED_CODE:");
                String suggestedCode = extractCodeBlock(response);

                return ImmutableMap.<String, Object>builder()
                    .put("status", "success")
                    .put("componentClassName", componentClassName)
                    .put("explanation", explanation != null ? explanation.trim() : "No explanation provided")
                    .put("suggestedFix", suggestedCode != null ? suggestedCode.trim() : response)
                    .put("violationCount", violations.size())
                    .put("filesAnalyzed", List.of(
                        tsPath != null ? Path.of(tsPath).getFileName().toString() : "",
                        htmlPath != null ? Path.of(htmlPath).getFileName().toString() : ""
                    ))
                    .build();

            } catch (Exception e) {
                log.error("Failed to generate fix suggestions", e);
                return ImmutableMap.<String, Object>builder()
                    .put("status", "error")
                    .put("message", "Failed to generate suggestions: " + e.getMessage())
                    .build();
            }
        });
    }

    /**
     * Extract a section from the AI response between two markers.
     */
    private String extractSection(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        if (start == -1) return null;
        start += startMarker.length();

        int end = text.indexOf(endMarker, start);
        if (end == -1) return text.substring(start);

        return text.substring(start, end);
    }

    /**
     * Extract code block from markdown code fence.
     */
    private String extractCodeBlock(String text) {
        int start = text.indexOf("```");
        if (start == -1) return null;

        // Skip the opening fence and language identifier
        int codeStart = text.indexOf("\n", start) + 1;
        int codeEnd = text.indexOf("```", codeStart);

        if (codeEnd == -1) return null;

        return text.substring(codeStart, codeEnd);
    }
}
