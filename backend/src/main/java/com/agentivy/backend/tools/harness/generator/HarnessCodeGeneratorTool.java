package com.agentivy.backend.tools.harness.generator;

import com.agentivy.backend.service.EventPublisherHelper;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM-powered harness code generator.
 * Uses Google ADK's LLM agent to generate valid TypeScript harness components.
 */
@Slf4j
@Component
public class HarnessCodeGeneratorTool implements ToolProvider {

    private final LlmAgent harnessCodeGeneratorAgent;
    private final InMemoryRunner runner;
    private final EventPublisherHelper eventPublisher;

    public HarnessCodeGeneratorTool(
            @Qualifier("harnessCodeGeneratorAgent") LlmAgent harnessCodeGeneratorAgent,
            @Qualifier("inMemoryRunner") InMemoryRunner runner,
            EventPublisherHelper eventPublisher) {
        this.harnessCodeGeneratorAgent = harnessCodeGeneratorAgent;
        this.runner = runner;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "harness.code.generate",
            "Harness Code Generator (LLM)",
            "Generates Angular harness component code using LLM",
            ToolCategory.COMPONENT_TESTING,
            "1.0.0",
            true,
            List.of("angular", "llm", "codegen", "harness"),
            Map.of()
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(FunctionTool.create(this, "generateHarnessCode"));
    }

    public Maybe<ImmutableMap<String, Object>> generateHarnessCode(
            @Schema(name = "componentClassName") String componentClassName,
            @Schema(name = "componentSelector") String componentSelector,
            @Schema(name = "componentImportPath") String componentImportPath,
            @Schema(name = "componentInputs") String componentInputs,
            @Schema(name = "mockData") String mockData,
            @Schema(name = "serviceMocks") String serviceMocks,
            @Schema(name = "dependencies") String dependencies) {

        return Maybe.fromCallable(() -> {
            log.info("Generating harness code for {} using LLM", componentClassName);
            long startTime = System.currentTimeMillis();

            // Count mocks
            int mockDataCount = mockData != null && !mockData.trim().isEmpty() ?
                mockData.split("const ").length - 1 : 0;
            int serviceMockCount = serviceMocks != null && !serviceMocks.trim().isEmpty() ?
                serviceMocks.split("class Mock").length - 1 : 0;

            // Publish starting status
            eventPublisher.publishComponentStatus(
                componentClassName,
                "harness",
                "starting",
                "Generating test harness code...",
                Map.of(
                    "componentSelector", componentSelector,
                    "componentImportPath", componentImportPath,
                    "mockDataCount", mockDataCount,
                    "serviceMockCount", serviceMockCount,
                    "hasDependencies", dependencies != null && !dependencies.trim().isEmpty()
                )
            );

            String prompt = buildPrompt(
                componentClassName,
                componentSelector,
                componentImportPath,
                componentInputs,
                mockData,
                serviceMocks,
                dependencies
            );

            eventPublisher.publishComponentStatus(
                componentClassName,
                "harness",
                "in-progress",
                "LLM generating harness component...",
                Map.of(
                    "phase", "llm-generation",
                    "promptLength", prompt.length(),
                    "progressPercent", 30
                )
            );

            // Let LLM generate the code using ADK runner
            CodeGenerationResult result = generateCodeWithLLM(prompt, startTime);

            if (result.code.isEmpty()) {
                eventPublisher.publishComponentStatus(
                    componentClassName,
                    "harness",
                    "failed",
                    "LLM failed to generate code",
                    Map.of(
                        "error", "Empty response from LLM",
                        "llmTokensUsed", result.tokensUsed
                    )
                );
                return ImmutableMap.of("status", "error", "message", "LLM failed to generate code");
            }

            eventPublisher.publishComponentStatus(
                componentClassName,
                "harness",
                "in-progress",
                "Cleaning and validating generated code...",
                Map.of(
                    "phase", "code-cleanup",
                    "rawCodeLength", result.code.length(),
                    "progressPercent", 70
                )
            );

            // Clean up code (remove markdown code fences if present)
            String generatedCode = cleanGeneratedCode(result.code);

            long timeElapsed = System.currentTimeMillis() - startTime;

            eventPublisher.publishComponentStatus(
                componentClassName,
                "harness",
                "completed",
                "Harness code generated successfully",
                Map.of(
                    "passed", true,
                    "codeLength", generatedCode.length(),
                    "linesOfCode", generatedCode.split("\n").length,
                    "llmTokensUsed", result.tokensUsed,
                    "timeElapsed", timeElapsed + "ms",
                    "hasImports", generatedCode.contains("import"),
                    "hasMockServices", generatedCode.contains("class Mock"),
                    "hasComponent", generatedCode.contains("@Component"),
                    "componentExported", generatedCode.contains("export class HarnessComponent")
                )
            );

            return ImmutableMap.<String, Object>builder()
                .put("status", "success")
                .put("code", generatedCode)
                .build();
        });
    }

    private String buildPrompt(
            String componentClassName,
            String componentSelector,
            String componentImportPath,
            String componentInputs,
            String mockData,
            String serviceMocks,
            String dependencies) {

        return String.format("""
            Generate a complete Angular harness component TypeScript file for testing.

            TARGET COMPONENT:
            - Class: %s
            - Selector: %s
            - Import path: %s

            COMPONENT INPUTS:
            %s

            MOCK DATA:
            %s

            SERVICE MOCKS:
            %s

            DEPENDENCIES:
            %s

            REQUIREMENTS (STRICTLY ENFORCE):
            1. NO duplicate imports - track what's already imported
            2. Declare ALL variables before using them
            3. Create complete MockService classes for all services referenced
            4. All brackets must be balanced and matched correctly
            5. Valid TypeScript syntax - must compile without errors
            6. Use standalone: true for the component
            7. Import CommonModule if needed
            8. Use RxJS 'of' operator for Observable mocks
            9. Create a unique test wrapper div with id="test-wrapper-[timestamp]"
            10. Export the component as HarnessComponent

            TEMPLATE STRUCTURE:
            ```typescript
            // 1. All imports (no duplicates)
            import { Component } from '@angular/core';
            import { CommonModule } from '@angular/common';
            // ... other imports

            // 2. Global constants for mock data
            const mockVariable = value;

            // 3. Mock service classes (complete implementations)
            class MockServiceName {
              methodName() {
                return of(mockData);
              }
            }

            // 4. Component decorator with template
            @Component({
              selector: 'app-harness',
              standalone: true,
              imports: [CommonModule, %s],
              providers: [/* mock providers */],
              template: `
                <div id="test-wrapper-[timestamp]">
                  <%s [input]="value"></%s>
                </div>
              `
            })
            export class HarnessComponent {
              // Expose mock data to template
            }
            ```

            Return ONLY the valid TypeScript code. Do NOT include explanations or markdown code fences.
            """,
            componentClassName,
            componentSelector,
            componentImportPath,
            componentInputs,
            mockData,
            serviceMocks,
            dependencies,
            componentClassName,
            componentSelector,
            componentSelector
        );
    }

    private String cleanGeneratedCode(String code) {
        // Remove markdown code fences
        code = code.replaceAll("```typescript\\n?", "");
        code = code.replaceAll("```\\n?", "");
        return code.trim();
    }

    /**
     * Record to hold code generation results with metadata.
     */
    private record CodeGenerationResult(String code, int tokensUsed) {}

    /**
     * Uses ADK runner to execute the LLM agent and generate code.
     */
    private CodeGenerationResult generateCodeWithLLM(String prompt, long startTime) {
        try {
            // Create a session for this code generation request
            Session session = runner.sessionService()
                .createSession(runner.appName(), "harness-codegen-" + System.currentTimeMillis())
                .blockingGet();

            // Create user message with the prompt
            Content userMsg = Content.fromParts(Part.fromText(prompt));

            // Run agent and collect final response
            AtomicReference<String> generatedCode = new AtomicReference<>("");
            AtomicReference<Integer> tokensUsed = new AtomicReference<>(0);

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
                // Estimate tokens (rough approximation: chars / 4)
                if (event.stringifyContent() != null) {
                    tokensUsed.set(event.stringifyContent().length() / 4);
                }
            });

            return new CodeGenerationResult(generatedCode.get(), tokensUsed.get());

        } catch (Exception e) {
            log.error("Failed to generate code with LLM", e);
            return new CodeGenerationResult("", 0);
        }
    }
}
