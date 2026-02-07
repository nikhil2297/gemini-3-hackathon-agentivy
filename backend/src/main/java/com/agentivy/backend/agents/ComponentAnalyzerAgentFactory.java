package com.agentivy.backend.agents;

import com.agentivy.backend.tools.registry.ToolRegistry;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.Gemini;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.tools.FunctionTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Factory that creates ComponentAnalyzerAgent with tools loaded at runtime.
 * This ensures tools are registered before the agent is created.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComponentAnalyzerAgentFactory {

    private final ToolRegistry toolRegistry;
    private final Gemini geminiLlmModel;

    /**
     * Creates a new InMemoryRunner with a fresh agent that has all registered tools.
     * Call this method at runtime to get tools that are definitely registered.
     */
    public InMemoryRunner createRunner() {
        // Get all enabled tools from the registry (at runtime, so they're definitely registered)
        List<FunctionTool> allTools = toolRegistry.getEnabledTools();

        log.info("Creating ComponentAnalyzerAgent with {} tools from registry (total: {}, enabled: {})",
            allTools.size(),
            toolRegistry.getToolCount(),
            toolRegistry.getEnabledToolCount()
        );

        if (allTools.isEmpty()) {
            log.error("WARNING: No tools available! Agent will not be able to call any functions.");
            log.error("Total tools in registry: {}", toolRegistry.getToolCount());
            log.error("Enabled tools: {}", toolRegistry.getEnabledToolCount());
        }

        LlmAgent agent = LlmAgent.builder()
                .name("ComponentAnalyzerAgent")
                .description("Autonomous Angular component analyzer and fixer")
                .model(geminiLlmModel)
                .instruction(AgentInstructions.getComponentAnalyzerInstruction())
                .tools(allTools)
                .build();

        return new InMemoryRunner(agent);
    }
}
