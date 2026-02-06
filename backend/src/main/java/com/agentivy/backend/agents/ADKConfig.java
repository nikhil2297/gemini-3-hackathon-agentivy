package com.agentivy.backend.agents;

import com.agentivy.backend.tools.registry.ToolRegistry;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.Gemini;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.tools.FunctionTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ADKConfig {

    private final ToolRegistry toolRegistry;

    @Value("${google.api.key}")
    private String googleApiKey;

    @Value("${google.model:gemini-3-pro-preview}")
    private String googleModel;

    @Bean
    public Gemini geminiLlmModel() {
        log.info("Initializing Gemini model: {}", googleModel);
        return Gemini.builder()
                .apiKey(googleApiKey)
                .modelName(googleModel)
                .build();
    }

    @Bean
    public LlmAgent harnessCodeGeneratorAgent(Gemini geminiLlmModel) {
        log.info("Initializing HarnessCodeGeneratorAgent");
        return LlmAgent.builder()
                .name("HarnessCodeGeneratorAgent")
                .description("Generates valid TypeScript harness components for Angular testing")
                .model(geminiLlmModel)
                .instruction("""
                    You are a TypeScript code generator specializing in Angular harness components.

                    CRITICAL RULES:
                    1. Generate ONLY valid TypeScript code
                    2. NO duplicate imports
                    3. Declare all variables before use
                    4. Create complete MockService classes
                    5. All brackets must match
                    6. Use standalone: true
                    7. Return ONLY code, no explanations
                    """)
                .build();
    }

    @Bean
    public LlmAgent accessibilityFixerAgent(Gemini geminiLlmModel) {
        log.info("Initializing AccessibilityFixerAgent");
        return LlmAgent.builder()
                .name("AccessibilityFixerAgent")
                .description("Fixes WCAG accessibility violations in Angular components")
                .model(geminiLlmModel)
                .instruction("""
                    You are an Angular accessibility expert specializing in fixing WCAG violations.

                    Your job is to receive component code and accessibility violations, then generate fixed code.

                    CRITICAL RULES:
                    1. Fix ALL accessibility violations following WCAG 2.1 AA standards
                    2. Add aria-label, aria-labelledby for interactive elements
                    3. Ensure 4.5:1 color contrast for text
                    4. Add alt text for images
                    5. Use semantic HTML (button not div with click)
                    6. Preserve ALL existing functionality
                    7. Return COMPLETE files, not just changes
                    8. Use the EXACT format: TYPESCRIPT_START...TYPESCRIPT_END, HTML_START...HTML_END
                    """)
                .build();
    }

    @Bean
    public LlmAgent performanceFixerAgent(Gemini geminiLlmModel) {
        log.info("Initializing PerformanceFixerAgent");
        return LlmAgent.builder()
                .name("PerformanceFixerAgent")
                .description("Fixes performance issues in Angular components")
                .model(geminiLlmModel)
                .instruction("""
                    You are an Angular performance expert specializing in optimizing components.

                    Your job is to receive component code and performance warnings, then generate optimized code.

                    CRITICAL OPTIMIZATIONS:
                    1. Add ChangeDetectionStrategy.OnPush to @Component decorator
                    2. Implement ngOnDestroy() with subscription cleanup
                    3. Use takeUntil pattern for RxJS subscriptions
                    4. Clear intervals and timeouts
                    5. Add trackBy for *ngFor loops
                    6. Move template functions to component properties or pure pipes
                    7. Remove blocking operations from constructor
                    8. Implement virtual scrolling for lists >100 items

                    RULES:
                    1. Preserve ALL existing functionality
                    2. Return COMPLETE files, not just changes
                    3. Add necessary imports (Subject, takeUntil, OnDestroy, ChangeDetectionStrategy)
                    4. Use the EXACT format: TYPESCRIPT_START...TYPESCRIPT_END, HTML_START...HTML_END
                    """)
                .build();
    }

    @Bean
    public InMemoryRunner inMemoryRunner(LlmAgent harnessCodeGeneratorAgent) {
        log.info("Initializing InMemoryRunner for code generation");
        return new InMemoryRunner(harnessCodeGeneratorAgent);
    }

    @Bean
    public InMemoryRunner accessibilityFixerRunner(LlmAgent accessibilityFixerAgent) {
        log.info("Initializing InMemoryRunner for accessibility fixing");
        return new InMemoryRunner(accessibilityFixerAgent);
    }

    @Bean
    public InMemoryRunner performanceFixerRunner(LlmAgent performanceFixerAgent) {
        log.info("Initializing InMemoryRunner for performance fixing");
        return new InMemoryRunner(performanceFixerAgent);
    }

    @Bean
    @org.springframework.context.annotation.Lazy
    public InMemoryRunner componentAnalyzerRunner(LlmAgent componentAnalyzerAgent) {
        log.info("Initializing InMemoryRunner for autonomous component analysis");
        return new InMemoryRunner(componentAnalyzerAgent);
    }

    @Bean
    @org.springframework.context.annotation.Lazy
    public LlmAgent componentAnalyzerAgent(Gemini geminiLlmModel) {
        // Get all enabled tools from the registry (lazy-loaded, so tools are registered by now)
        List<FunctionTool> allTools = toolRegistry.getEnabledTools();

        log.info("Loaded {} enabled tools from registry (total: {}, enabled: {})",
            allTools.size(),
            toolRegistry.getToolCount(),
            toolRegistry.getEnabledToolCount()
        );

        return LlmAgent.builder()
                .name("ComponentAnalyzerAgent")
                .description("Analyzes Angular components for accessibility issues")
                .model(geminiLlmModel)
                .instruction(AgentInstructions.getComponentAnalyzerInstruction())
                .tools(allTools)
                .build();
    }
}