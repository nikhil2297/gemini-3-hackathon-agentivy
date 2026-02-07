package com.agentivy.backend.tools.registry;

import com.google.adk.tools.FunctionTool;

import java.util.List;

/**
 * Interface that all tools must implement to be registered in the ToolRegistry.
 *
 * This interface standardizes how tools expose their metadata and create
 * ADK FunctionTools, enabling dynamic tool discovery and registration.
 *
 * IMPLEMENTATION PATTERN:
 * 1. Annotate your tool class with @Component
 * 2. Implement this interface
 * 3. Define metadata in getMetadata()
 * 4. Return FunctionTools in createTools()
 * 5. Spring will auto-discover and register your tool
 *
 * EXAMPLE:
 * <pre>
 * {@code
 * @Component
 * public class MyTool implements ToolProvider {
 *
 *     @Override
 *     public ToolMetadata getMetadata() {
 *         return new ToolMetadata(
 *             "my.tool",
 *             "My Tool",
 *             "Does something useful",
 *             ToolCategory.CODE_ANALYSIS,
 *             "1.0.0",
 *             true,
 *             List.of("analysis", "utility"),
 *             Map.of()
 *         );
 *     }
 *
 *     @Override
 *     public List<FunctionTool> createTools() {
 *         return List.of(FunctionTool.create(this, "myMethod"));
 *     }
 *
 *     public Maybe<ImmutableMap<String, Object>> myMethod(String param) {
 *         // Tool implementation
 *     }
 * }
 * }
 * </pre>
 */
public interface ToolProvider {

    /**
     * Returns metadata describing this tool's capabilities.
     *
     * @return ToolMetadata containing toolId, display name, description, category, etc.
     */
    ToolMetadata getMetadata();

    /**
     * Creates the ADK FunctionTools exposed by this provider.
     *
     * @return List of FunctionTools that will be registered with the agent
     */
    List<FunctionTool> createTools();
}
