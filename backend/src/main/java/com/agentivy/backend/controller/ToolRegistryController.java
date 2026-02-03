package com.agentivy.backend.controller;

import com.agentivy.backend.tools.registry.ToolCategory;
import com.agentivy.backend.tools.registry.ToolMetadata;
import com.agentivy.backend.tools.registry.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for inspecting the Tool Registry.
 *
 * Provides endpoints to:
 * - View all registered tools
 * - Filter tools by category
 * - Search tools by tag
 * - Enable/disable tools at runtime
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolRegistryController {

    private final ToolRegistry toolRegistry;

    /**
     * Get summary of all tools in the registry.
     *
     * GET /api/tools
     */
    @GetMapping
    public Map<String, Object> getAllTools() {
        List<ToolMetadata> allTools = toolRegistry.getAllTools();

        Map<String, Object> response = new HashMap<>();
        response.put("totalTools", toolRegistry.getToolCount());
        response.put("enabledTools", toolRegistry.getEnabledToolCount());
        response.put("tools", allTools);

        // Group by category
        Map<String, List<ToolMetadata>> byCategory = new HashMap<>();
        for (ToolCategory category : ToolCategory.values()) {
            List<ToolMetadata> categoryTools = toolRegistry.getToolsByCategory(category);
            if (!categoryTools.isEmpty()) {
                byCategory.put(category.name(), categoryTools);
            }
        }
        response.put("byCategory", byCategory);

        return response;
    }

    /**
     * Get tools in a specific category.
     *
     * GET /api/tools/category/SOURCE_CONTROL
     */
    @GetMapping("/category/{category}")
    public Map<String, Object> getToolsByCategory(@PathVariable String category) {
        try {
            ToolCategory toolCategory = ToolCategory.valueOf(category);
            List<ToolMetadata> tools = toolRegistry.getToolsByCategory(toolCategory);

            Map<String, Object> response = new HashMap<>();
            response.put("category", toolCategory.getDisplayName());
            response.put("description", toolCategory.getDescription());
            response.put("tools", tools);
            response.put("count", tools.size());

            return response;
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid category: " + category);
            error.put("validCategories", List.of(ToolCategory.values()));
            return error;
        }
    }

    /**
     * Search tools by tag.
     *
     * GET /api/tools/search?tag=angular
     */
    @GetMapping("/search")
    public Map<String, Object> searchByTag(@RequestParam String tag) {
        List<ToolMetadata> tools = toolRegistry.searchByTag(tag);

        Map<String, Object> response = new HashMap<>();
        response.put("tag", tag);
        response.put("tools", tools);
        response.put("count", tools.size());

        return response;
    }

    /**
     * Get metadata for a specific tool.
     *
     * GET /api/tools/github.operations
     */
    @GetMapping("/{toolId}")
    public Map<String, Object> getToolMetadata(@PathVariable String toolId) {
        return toolRegistry.getMetadata(toolId)
            .map(metadata -> {
                Map<String, Object> response = new HashMap<>();
                response.put("found", true);
                response.put("metadata", metadata);
                return response;
            })
            .orElseGet(() -> {
                Map<String, Object> response = new HashMap<>();
                response.put("found", false);
                response.put("error", "Tool not found: " + toolId);
                return response;
            });
    }

    /**
     * Enable or disable a tool at runtime.
     *
     * POST /api/tools/github.operations/enable
     * POST /api/tools/github.operations/disable
     */
    @PostMapping("/{toolId}/{action}")
    public Map<String, Object> setToolEnabled(
            @PathVariable String toolId,
            @PathVariable String action) {

        if (!action.equals("enable") && !action.equals("disable")) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Invalid action. Use 'enable' or 'disable'");
            return error;
        }

        boolean enable = action.equals("enable");
        toolRegistry.setEnabled(toolId, enable);

        Map<String, Object> response = new HashMap<>();
        response.put("toolId", toolId);
        response.put("action", action);
        response.put("newState", enable ? "enabled" : "disabled");
        response.put("totalEnabled", toolRegistry.getEnabledToolCount());

        return response;
    }

    /**
     * Get registry statistics.
     *
     * GET /api/tools/stats
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTools", toolRegistry.getToolCount());
        stats.put("enabledTools", toolRegistry.getEnabledToolCount());
        stats.put("disabledTools", toolRegistry.getToolCount() - toolRegistry.getEnabledToolCount());

        // Count by category
        Map<String, Integer> byCategory = new HashMap<>();
        for (ToolCategory category : ToolCategory.values()) {
            int count = toolRegistry.getToolsByCategory(category).size();
            if (count > 0) {
                byCategory.put(category.name(), count);
            }
        }
        stats.put("toolsByCategory", byCategory);

        return stats;
    }
}
