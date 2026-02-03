package com.agentivy.backend.tools.github.model;

/**
 * Represents an Angular component with its associated files and classification type.
 *
 * @param name The component class name (e.g., "TaskListPageComponent")
 * @param typescriptPath Absolute path to the TypeScript file
 * @param templatePath Absolute path to the HTML template (may be null)
 * @param stylesPath Absolute path to the styles file (may be null)
 * @param type The classification type (PAGE, COMPONENT, or ELEMENT)
 * @param relativePath Relative path from project root (e.g., "src/app/pages/task-list-page")
 */
public record ClassifiedComponent(
    String name,
    String typescriptPath,
    String templatePath,
    String stylesPath,
    ComponentType type,
    String relativePath
) {
}
