package com.agentivy.backend.tools.registry;

import java.util.List;
import java.util.Map;

/**
 * Metadata describing a tool's capabilities and configuration.
 *
 * This record provides rich metadata for tools, enabling:
 * - Dynamic tool discovery
 * - Runtime enable/disable functionality
 * - Categorization and tagging for organization
 * - Tool-specific configuration
 */
public record ToolMetadata(
    String toolId,              // Unique identifier (e.g., "github.clone")
    String displayName,         // User-facing name (e.g., "Clone GitHub Repository")
    String description,         // What the tool does
    ToolCategory category,      // Logical grouping
    String version,             // Semantic versioning (e.g., "1.0.0")
    boolean enabled,            // Runtime enable/disable flag
    List<String> tags,          // Searchable tags (e.g., ["git", "repository", "clone"])
    Map<String, Object> config  // Tool-specific configuration
) {
    /**
     * Creates a new ToolMetadata with the enabled flag toggled.
     */
    public ToolMetadata withEnabled(boolean enabled) {
        return new ToolMetadata(
            toolId,
            displayName,
            description,
            category,
            version,
            enabled,
            tags,
            config
        );
    }
}
