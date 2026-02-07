package com.agentivy.backend.tools.registry;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring configuration that auto-registers all ToolProviders with the ToolRegistry.
 *
 * MECHANISM:
 * Spring's dependency injection automatically discovers all @Component classes
 * that implement ToolProvider and injects them into this configuration's
 * constructor via the List<ToolProvider> parameter.
 *
 * The @PostConstruct method then registers each provider with the ToolRegistry
 * after the Spring context is fully initialized.
 *
 * ADDING NEW TOOLS:
 * To add a new tool:
 * 1. Create a class that implements ToolProvider
 * 2. Annotate it with @Component
 * 3. Spring will automatically discover and register it
 * 4. No changes to this file are needed
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ToolRegistryConfig {

    private final ToolRegistry toolRegistry;
    private final List<ToolProvider> toolProviders;

    /**
     * Registers all discovered ToolProviders with the ToolRegistry.
     *
     * This method is called automatically by Spring after all beans are constructed.
     */
    @PostConstruct
    public void registerAllTools() {
        log.info("Registering {} tool provider(s) with ToolRegistry...", toolProviders.size());

        toolProviders.forEach(toolRegistry::register);

        log.info("Tool registration complete. Total tools: {}, Enabled: {}",
            toolRegistry.getToolCount(),
            toolRegistry.getEnabledToolCount()
        );

        // Log summary by category
        for (ToolCategory category : ToolCategory.values()) {
            List<ToolMetadata> categoryTools = toolRegistry.getToolsByCategory(category);
            if (!categoryTools.isEmpty()) {
                log.info("  {} - {} tool(s): {}",
                    category.getDisplayName(),
                    categoryTools.size(),
                    categoryTools.stream()
                        .map(ToolMetadata::displayName)
                        .toList()
                );
            }
        }
    }
}
