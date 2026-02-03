package com.agentivy.backend.tools.harness.metadata;

import java.util.List;
import java.util.Map;

/**
 * Metadata extracted from an Angular component.
 */
public record ComponentMetadata(
    String className,
    String selector,
    String importPath,
    List<ComponentInput> inputs,
    List<ComponentOutput> outputs,
    List<ServiceDependency> dependencies,
    Map<String, String> existingImports
) {
    public record ComponentInput(String name, String type) {}
    public record ComponentOutput(String name, String type) {}
    public record ServiceDependency(String className, String importPath) {}
}
