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
 * Atomic tool for automatically fixing performance issues in Angular components.
 * Uses LLM to generate optimizations for memory leaks, excessive change detection, and DOM complexity.
 */
@Slf4j
@Component
public class PerformanceFixerTool implements ToolProvider {

    private final LlmAgent performanceFixerAgent;
    private final InMemoryRunner runner;

    public PerformanceFixerTool(
            @Qualifier("performanceFixerAgent") LlmAgent performanceFixerAgent,
            @Qualifier("performanceFixerRunner") InMemoryRunner runner) {
        this.performanceFixerAgent = performanceFixerAgent;
        this.runner = runner;
    }

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "fixing.performance",
            "Performance Fixer Tool",
            "Automatically fixes performance issues using LLM-powered code optimization",
            ToolCategory.CODE_FIXING,
            "1.0.0",
            true,
            List.of("performance", "optimization", "fixing", "memory-leak", "change-detection", "llm"),
            Map.of(
                "capabilities", "Fixes memory leaks, excessive change detection, DOM complexity, blocking operations",
                "techniques", "OnPush strategy, virtual scrolling, subscription cleanup, detach change detector"
            )
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(FunctionTool.create(this, "fixPerformanceIssues"));
    }

    /**
     * Fixes performance issues in a component.
     *
     * @param repoPath Path to the Angular repository
     * @param componentClassName Name of the component class (e.g., "PerformanceIssueComponent")
     * @param performanceWarnings JSON string containing warnings from ComponentPerformanceTool
     * @param performanceMetrics JSON string containing full performance metrics
     * @return Result with applied fixes and file changes
     */
    public Maybe<ImmutableMap<String, Object>> fixPerformanceIssues(
            @Schema(name = "repoPath") String repoPath,
            @Schema(name = "componentClassName") String componentClassName,
            @Schema(name = "performanceWarnings") String performanceWarnings,
            @Schema(name = "performanceMetrics") String performanceMetrics) {

        return Maybe.fromCallable(() -> {
            log.info("Fixing performance issues for component: {}", componentClassName);

            try {
                // Find component files
                Path repoRoot = Path.of(repoPath);
                Path srcPath = repoRoot.resolve("src");

                Optional<Path> tsFile = findComponentFile(srcPath, componentClassName, ".component.ts");
                Optional<Path> htmlFile = findComponentFile(srcPath, componentClassName, ".component.html");

                if (tsFile.isEmpty()) {
                    return ImmutableMap.<String, Object>builder()
                        .put("status", "error")
                        .put("message", "Component TypeScript file not found for: " + componentClassName)
                        .build();
                }

                List<Map<String, String>> appliedFixes = new ArrayList<>();

                // Read current file contents
                String originalTs = Files.readString(tsFile.get());
                String originalHtml = htmlFile.isPresent() ? Files.readString(htmlFile.get()) : "";

                // Generate fixes using LLM
                log.info("Generating performance fixes using LLM...");
                Map<String, String> fixes = generatePerformanceFixes(
                    componentClassName,
                    originalTs,
                    originalHtml,
                    performanceWarnings,
                    performanceMetrics
                );

                // Apply TypeScript fixes
                if (fixes.containsKey("typescript") && !fixes.get("typescript").equals(originalTs)) {
                    String fixedTs = fixes.get("typescript");
                    Files.writeString(tsFile.get(), fixedTs);
                    appliedFixes.add(Map.of(
                        "file", tsFile.get().getFileName().toString(),
                        "type", "component",
                        "description", "Applied performance optimizations to component logic"
                    ));
                    log.info("✓ Applied TypeScript performance fixes to: {}", tsFile.get().getFileName());
                }

                // Apply HTML fixes
                if (htmlFile.isPresent() && fixes.containsKey("html") && !fixes.get("html").equals(originalHtml)) {
                    String fixedHtml = fixes.get("html");
                    Files.writeString(htmlFile.get(), fixedHtml);
                    appliedFixes.add(Map.of(
                        "file", htmlFile.get().getFileName().toString(),
                        "type", "template",
                        "description", "Applied performance optimizations to template"
                    ));
                    log.info("✓ Applied HTML performance fixes to: {}", htmlFile.get().getFileName());
                }

                if (appliedFixes.isEmpty()) {
                    return ImmutableMap.<String, Object>builder()
                        .put("status", "success")
                        .put("message", "No fixes needed - component performance is optimal")
                        .put("appliedFixes", appliedFixes)
                        .build();
                }

                return ImmutableMap.<String, Object>builder()
                    .put("status", "success")
                    .put("message", String.format("Applied %d performance fix(es)", appliedFixes.size()))
                    .put("appliedFixes", appliedFixes)
                    .put("explanation", fixes.getOrDefault("explanation", ""))
                    .put("optimizations", parseOptimizations(fixes.getOrDefault("explanation", "")))
                    .build();

            } catch (Exception e) {
                log.error("Failed to fix performance issues", e);
                return ImmutableMap.<String, Object>builder()
                    .put("status", "error")
                    .put("message", "Fix generation failed: " + e.getMessage())
                    .build();
            }
        });
    }

    /**
     * Generate performance fixes using LLM.
     */
    private Map<String, String> generatePerformanceFixes(
            String componentClassName,
            String tsCode,
            String htmlCode,
            String warnings,
            String metrics) {

        String prompt = buildPerformanceFixPrompt(componentClassName, tsCode, htmlCode, warnings, metrics);

        log.info("Sending performance fix request to LLM...");
        String response = generateCodeWithLLM(prompt);

        return parseFixResponse(response, tsCode, htmlCode);
    }

    /**
     * Build prompt for LLM to generate performance fixes.
     */
    private String buildPerformanceFixPrompt(
            String componentClassName,
            String tsCode,
            String htmlCode,
            String warnings,
            String metrics) {

        return String.format("""
            You are an Angular performance expert. Fix the performance issues in this component.

            Component: %s

            TypeScript Code:
            ```typescript
            %s
            ```

            HTML Template:
            ```html
            %s
            ```

            Performance Warnings:
            ```json
            %s
            ```

            Performance Metrics:
            ```json
            %s
            ```

            INSTRUCTIONS:
            1. Fix all performance issues. Common optimizations:

               MEMORY LEAKS:
               - Add ngOnDestroy() lifecycle hook
               - Unsubscribe from all observables
               - Clear all intervals and timeouts
               - Use takeUntil pattern with Subject for RxJS subscriptions
               - Remove event listeners in ngOnDestroy

               EXCESSIVE CHANGE DETECTION:
               - Add ChangeDetectionStrategy.OnPush to @Component decorator
               - Use ChangeDetectorRef.detach() for components that update infrequently
               - Move functions from templates to component properties or pure pipes
               - Avoid expensive calculations in templates
               - Use trackBy for *ngFor loops
               - Debounce rapid updates

               DOM COMPLEXITY:
               - Implement virtual scrolling with @angular/cdk/scrolling
               - Add pagination for large lists
               - Lazy load content that's off-screen
               - Reduce unnecessary DOM elements

               BLOCKING OPERATIONS:
               - Move heavy calculations to Web Workers
               - Use async/await for non-blocking operations
               - Remove blocking constructor code
               - Defer non-critical initialization

            2. Preserve all existing functionality and public API
            3. Add necessary imports (e.g., Subject, takeUntil, OnDestroy, ChangeDetectionStrategy)
            4. Return the fixed code in this EXACT format:

            TYPESCRIPT_START
            [complete fixed TypeScript code here]
            TYPESCRIPT_END

            HTML_START
            [complete fixed HTML template here]
            HTML_END

            EXPLANATION_START
            [detailed explanation listing each optimization applied]
            EXPLANATION_END

            IMPORTANT:
            - Include ALL original code, not just changes. Return complete files.
            - Be aggressive with optimizations - this component has serious performance problems.
            - Add OnPush change detection strategy
            - Implement ngOnDestroy with proper cleanup
            - Fix all memory leaks and subscriptions
            """,
            componentClassName,
            tsCode,
            htmlCode.isEmpty() ? "<!-- No HTML template -->" : htmlCode,
            warnings,
            metrics
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
     * Parse optimization list from explanation.
     */
    private List<String> parseOptimizations(String explanation) {
        List<String> optimizations = new ArrayList<>();

        // Extract bullet points or numbered items
        String[] lines = explanation.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.matches("^[\\d\\-\\*]+\\s*.+")) {
                // Remove leading numbers, dashes, or asterisks
                String optimization = line.replaceAll("^[\\d\\-\\*]+\\.?\\s*", "");
                if (!optimization.isEmpty()) {
                    optimizations.add(optimization);
                }
            }
        }

        return optimizations.isEmpty() ? List.of(explanation) : optimizations;
    }

    /**
     * Uses ADK runner to execute the LLM agent and generate fixes.
     */
    private String generateCodeWithLLM(String prompt) {
        try {
            // Create a session for this fix generation request
            Session session = runner.sessionService()
                .createSession(runner.appName(), "performance-fixer-" + System.currentTimeMillis())
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
     * Suggest fixes for performance issues without applying them.
     * This method generates AI-powered optimization recommendations but does not modify any files.
     *
     * @param repoPath Path to the Angular repository
     * @param componentClassName Name of the component class
     * @param tsPath Path to TypeScript file
     * @param warnings List of performance warnings
     * @param metrics Performance metrics
     * @return Suggested optimizations with explanations
     */
    public Maybe<ImmutableMap<String, Object>> suggestFixes(
            String repoPath,
            String componentClassName,
            String tsPath,
            List<String> warnings,
            Map<String, Object> metrics) {

        return Maybe.fromCallable(() -> {
            log.info("Generating performance fix suggestions for component: {}", componentClassName);

            try {
                // Read component file
                String tsContent = tsPath != null && Files.exists(Path.of(tsPath))
                    ? Files.readString(Path.of(tsPath))
                    : "";

                // Build prompt for AI to generate suggestions
                StringBuilder prompt = new StringBuilder();
                prompt.append("Analyze the following Angular component and suggest performance optimizations.\n\n");
                prompt.append("Component: ").append(componentClassName).append("\n\n");

                if (!tsContent.isEmpty()) {
                    prompt.append("TypeScript:\n```typescript\n")
                        .append(tsContent)
                        .append("\n```\n\n");
                }

                prompt.append("Performance Metrics:\n");
                metrics.forEach((key, value) ->
                    prompt.append("  ").append(key).append(": ").append(value).append("\n")
                );
                prompt.append("\n");

                prompt.append("Performance Warnings:\n");
                for (int i = 0; i < warnings.size(); i++) {
                    prompt.append(i + 1).append(". ").append(warnings.get(i)).append("\n");
                }
                prompt.append("\n");

                prompt.append("Provide:\n");
                prompt.append("1. A clear explanation of the performance issues and why they occur\n");
                prompt.append("2. Specific optimizations needed (OnPush strategy, trackBy, unsubscribe, etc.)\n");
                prompt.append("3. The complete optimized code\n\n");
                prompt.append("Format your response as:\n");
                prompt.append("EXPLANATION:\n[Your explanation here]\n\n");
                prompt.append("SUGGESTED_CODE:\n```typescript\n[Optimized TypeScript code]\n```\n");

                // Use LLM to generate suggestions
                Session session = runner.sessionService()
                    .createSession(runner.appName(), "perf-suggest-" + System.currentTimeMillis())
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
                    .put("warningCount", warnings.size())
                    .put("metrics", metrics)
                    .build();

            } catch (Exception e) {
                log.error("Failed to generate performance fix suggestions", e);
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
