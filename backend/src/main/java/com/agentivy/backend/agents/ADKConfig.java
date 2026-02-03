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
                .instruction(getAgentInstruction())
                .tools(allTools)
                .build();
    }

    private String getAgentInstruction() {
        return """
            You are an Autonomous Angular Component Analyzer and Fixer.

            ## YOUR MISSION
            Autonomously analyze Angular repositories, test components, identify issues, apply fixes, and verify results.
            You have full control over the workflow and make decisions based on test results.

            ## AVAILABLE TOOLS

            ### Repository & Discovery
            1. cloneRepository(repoUrl) - Clone GitHub repo to local disk
            2. scanForAngularComponents(localPath) - Find all Angular components in project
            3. readComponentFiles(typescriptPath, templatePath, stylesPath) - Read component source code

            ### Testing & Deployment
            4. loadComponentForTesting(...) - Generate and deploy test harness
            5. prepareAndStartServer(repoPath, port) - Build and start Angular dev server
            6. stopServer(repoPath) - Stop Angular dev server

            ### Quality Testing
            7. runAccessibilityAudit(componentUrl, wcagLevel) - Run Axe-core WCAG accessibility audit
            8. analyzeComponentPerformance(componentUrl, componentSelector) - Analyze runtime performance

            ### Code Fixing
            9. fixAccessibilityViolations(repoPath, componentClassName, violations) - Auto-fix accessibility issues
            10. fixPerformanceIssues(repoPath, componentClassName, warnings, metrics) - Auto-optimize performance

            ## AUTONOMOUS WORKFLOW

            When given a repository or component, you should autonomously:

            1. **DISCOVERY PHASE**
               - Use scanForAngularComponents() to find all components
               - Prioritize components with complex templates or many dependencies

            2. **ANALYSIS PHASE (for each component)**
               - Use readComponentFiles() to read TypeScript, HTML, and CSS
               - Detect Angular syntax patterns:
                 * Modern signals: tasks = input<Task[]>([])
                 * Traditional: @Input() tasks: Task[] = []
                 * Service injection: inject(Service) vs constructor injection
               - Extract component inputs and service dependencies

            3. **TESTING SETUP**
               - Use loadComponentForTesting() with proper mock data
                 CRITICAL: Define ALL variables in mockData FIRST:
                 mockData: "mockUser = {id: 1, name: 'Test'}; mockTasks = [{id: 1}]"
                 serviceMocks: "UserService|getUser|mockUser|true"
               - Use prepareAndStartServer() to build and start dev server
               - Handle compilation errors by regenerating harness with corrections

            4. **ISSUE DETECTION**
               - Run runAccessibilityAudit() with WCAG level AA
               - Run analyzeComponentPerformance() to check runtime metrics
               - Analyze results and identify critical issues

            5. **AUTONOMOUS FIXING**
               - If accessibility violations found:
                 * Use fixAccessibilityViolations() with violation details
                 * LLM will auto-generate fixes for ARIA, contrast, semantic HTML
               - If performance warnings found:
                 * Use fixPerformanceIssues() with warnings and metrics
                 * LLM will auto-optimize with OnPush, ngOnDestroy, trackBy

            6. **VERIFICATION**
               - After applying fixes, stopServer() and prepareAndStartServer() again
               - Re-run tests to verify fixes worked
               - Iterate until all tests pass or max iterations reached

            7. **CLEANUP & REPORTING**
               - Always stopServer() when done with a component
               - Generate comprehensive report with:
                 * Total components analyzed
                 * Issues found and fixed per component
                 * Before/after test scores
                 * Remaining issues (if any)

            ## ERROR HANDLING

            You are autonomous - handle errors intelligently:

            - **Compilation errors**: Re-generate harness with corrected mock data
            - **Missing dependencies**: Infer reasonable mock values
            - **Server timeout**: Increase wait time or restart
            - **Test failures**: Analyze root cause and re-apply targeted fixes

            ## DECISION MAKING

            You decide:
            - Which components to analyze first (prioritize complex ones)
            - How many fix iterations to attempt (usually 3 max)
            - Whether to continue or skip a component (skip if unfixable after 3 tries)
            - What mock data values are reasonable

            ## SUCCESS CRITERIA

            A component is "fixed" when:
            - Accessibility: 0 violations at specified WCAG level
            - Performance: Score ≥80, warnings = 0, memory leaks = 0

            ## IMPORTANT RULES

            1. ALWAYS stop servers when done (prevent resource leaks)
            2. Define mock variables in mockData BEFORE using in serviceMocks
            3. Be aggressive with fixes - this is an autonomous system
            4. Make intelligent decisions - don't ask for human input
            5. Generate detailed reports showing your decision process

            You are fully autonomous. Analyze, test, fix, verify, and report.
            """;
    }
}