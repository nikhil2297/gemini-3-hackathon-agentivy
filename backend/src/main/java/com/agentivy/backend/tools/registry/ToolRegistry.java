package com.agentivy.backend.tools.registry;

import com.google.adk.tools.FunctionTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for managing all tools in the system.
 *
 * The ToolRegistry provides:
 * - Dynamic tool registration and discovery
 * - Runtime enable/disable functionality
 * - Category-based filtering
 * - Tag-based search
 * - Tool metadata access
 *
 * THREAD SAFETY:
 * This class is thread-safe using ConcurrentHashMap for internal storage.
 *
 * USAGE:
 * Tools are auto-registered via ToolRegistryConfig during Spring initialization.
 * Use getEnabledTools() to retrieve all enabled tools for the ADK agent.
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, ToolMetadata> registeredTools = new ConcurrentHashMap<>();
    private final Map<String, ToolProvider> toolProviders = new ConcurrentHashMap<>();

    /**
     * Registers a tool provider with the registry.
     *
     * @param provider The ToolProvider to register
     */
    public void register(ToolProvider provider) {
        ToolMetadata metadata = provider.getMetadata();
        registeredTools.put(metadata.toolId(), metadata);
        toolProviders.put(metadata.toolId(), provider);
        log.info("Registered tool: {} [{}] - {}",
            metadata.displayName(),
            metadata.category().getDisplayName(),
            metadata.description()
        );
    }

    /**
     * Enables or disables a tool at runtime.
     *
     * @param toolId The unique tool identifier
     * @param enabled Whether the tool should be enabled
     */
    public void setEnabled(String toolId, boolean enabled) {
        ToolMetadata current = registeredTools.get(toolId);
        if (current != null) {
            registeredTools.put(toolId, current.withEnabled(enabled));
            log.info("Tool {} is now {}", toolId, enabled ? "enabled" : "disabled");
        } else {
            log.warn("Attempted to set enabled status for unknown tool: {}", toolId);
        }
    }

    /**
     * Returns all FunctionTools from enabled tool providers.
     *
     * This method is used by ADKConfig to gather all tools for the agent.
     *
     * @return List of FunctionTools from all enabled providers
     */
    public List<FunctionTool> getEnabledTools() {
        return toolProviders.values().stream()
            .filter(provider -> isEnabled(provider.getMetadata().toolId()))
            .flatMap(provider -> provider.createTools().stream())
            .toList();
    }

    /**
     * Returns all tools in a specific category.
     *
     * @param category The ToolCategory to filter by
     * @return List of ToolMetadata for tools in this category
     */
    public List<ToolMetadata> getToolsByCategory(ToolCategory category) {
        return registeredTools.values().stream()
            .filter(m -> m.category() == category)
            .toList();
    }

    /**
     * Returns metadata for a specific tool.
     *
     * @param toolId The unique tool identifier
     * @return Optional containing the ToolMetadata if found
     */
    public Optional<ToolMetadata> getMetadata(String toolId) {
        return Optional.ofNullable(registeredTools.get(toolId));
    }

    /**
     * Checks if a tool is currently enabled.
     *
     * @param toolId The unique tool identifier
     * @return true if the tool exists and is enabled, false otherwise
     */
    public boolean isEnabled(String toolId) {
        return getMetadata(toolId)
            .map(ToolMetadata::enabled)
            .orElse(false);
    }

    /**
     * Searches for tools by tag.
     *
     * @param tag The tag to search for
     * @return List of ToolMetadata for tools with this tag
     */
    public List<ToolMetadata> searchByTag(String tag) {
        return registeredTools.values().stream()
            .filter(m -> m.tags().contains(tag))
            .toList();
    }

    /**
     * Returns all registered tool metadata.
     *
     * @return List of all ToolMetadata (both enabled and disabled)
     */
    public List<ToolMetadata> getAllTools() {
        return List.copyOf(registeredTools.values());
    }

    /**
     * Returns the total count of registered tools.
     *
     * @return Number of registered tools
     */
    public int getToolCount() {
        return registeredTools.size();
    }

    /**
     * Returns the count of enabled tools.
     *
     * @return Number of enabled tools
     */
    public int getEnabledToolCount() {
        return (int) registeredTools.values().stream()
            .filter(ToolMetadata::enabled)
            .count();
    }
}
