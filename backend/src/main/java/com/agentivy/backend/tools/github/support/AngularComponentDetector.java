package com.agentivy.backend.tools.github.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Detects and analyzes Angular components in a repository.
 *
 * Responsibilities:
 * - Scanning directories for .component.ts files
 * - Discovering associated template and style files
 * - Extracting component metadata
 */
@Slf4j
@Component
public class AngularComponentDetector {

    /**
     * Represents information about an Angular component.
     */
    public record ComponentInfo(
        String name,                    // PascalCase name (e.g., "SettingsComponent")
        String relativePath,            // Parent directory relative to root (e.g., "src/app/admin/settings")
        String fullName,                // Unique identifier (e.g., "admin/settings/SettingsComponent")
        String tsPath,                  // Relative TS path
        Optional<String> htmlPath,      // Relative HTML path
        Optional<String> cssPath        // Relative CSS/SCSS path
    ) {}

    /**
     * Finds all Angular components in a directory tree.
     *
     * @param rootPath Root directory to search
     * @return List of discovered components
     * @throws IOException if directory traversal fails
     */
    public List<ComponentInfo> findComponents(Path rootPath) throws IOException {
        if (!Files.exists(rootPath)) {
            log.warn("Root path does not exist: {}", rootPath);
            return List.of();
        }

        List<ComponentInfo> components = new ArrayList<>();

        Files.walk(rootPath)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".component.ts"))
                .filter(path -> !path.toString().contains("node_modules"))
                .filter(path -> !path.toString().endsWith(".spec.ts"))
                .forEach(path -> {
                    try {
                        ComponentInfo info = extractComponentInfo(path, rootPath);
                        components.add(info);
                        log.debug("Found component: {} ({})", info.name(), info.fullName());
                    } catch (Exception e) {
                        log.warn("Error processing component {}: {}", path, e.getMessage());
                    }
                });

        log.info("Found {} Angular components in {}", components.size(), rootPath);
        return components;
    }

    /**
     * Extracts component information from a .component.ts file.
     *
     * @param tsPath Path to the TypeScript component file
     * @param rootPath Root path of the repository for calculating relative paths
     * @return ComponentInfo with relative paths to associated files
     */
    private ComponentInfo extractComponentInfo(Path tsPath, Path rootPath) {
        String componentName = extractComponentName(tsPath);
        Path parentDir = tsPath.getParent();
        String baseName = tsPath.getFileName().toString().replace(".component.ts", "");

        // Calculate relative paths (normalize to forward slashes for cross-platform consistency)
        String tsRelativePath = normalizePath(rootPath.relativize(tsPath));
        String relativeParentPath = normalizePath(rootPath.relativize(parentDir));

        // Build fullName from relative parent path + component name
        // e.g., "src/app/admin/settings" -> "admin/settings/SettingsComponent"
        String fullName = buildFullName(relativeParentPath, componentName);

        // Look for associated template file
        Path templatePath = parentDir.resolve(baseName + ".component.html");
        Optional<String> htmlRelativePath = Files.exists(templatePath)
            ? Optional.of(normalizePath(rootPath.relativize(templatePath)))
            : Optional.empty();

        // Look for associated style files (SCSS preferred over CSS)
        Path scssPath = parentDir.resolve(baseName + ".component.scss");
        Path cssPath = parentDir.resolve(baseName + ".component.css");
        Optional<String> cssRelativePath;
        if (Files.exists(scssPath)) {
            cssRelativePath = Optional.of(normalizePath(rootPath.relativize(scssPath)));
        } else if (Files.exists(cssPath)) {
            cssRelativePath = Optional.of(normalizePath(rootPath.relativize(cssPath)));
        } else {
            cssRelativePath = Optional.empty();
        }

        return new ComponentInfo(
            componentName,
            relativeParentPath,
            fullName,
            tsRelativePath,
            htmlRelativePath,
            cssRelativePath
        );
    }

    /**
     * Normalizes a path to use forward slashes for cross-platform consistency.
     */
    private String normalizePath(Path path) {
        return path.toString().replace("\\", "/");
    }

    /**
     * Builds a unique full name from the relative path and component name.
     * Extracts meaningful path segments (skips common prefixes like src/app).
     *
     * @param relativePath Relative parent directory path
     * @param componentName PascalCase component name
     * @return Full name like "admin/settings/SettingsComponent"
     */
    private String buildFullName(String relativePath, String componentName) {
        // Remove common Angular prefixes to get meaningful path
        String meaningfulPath = relativePath
            .replaceFirst("^src/app/", "")
            .replaceFirst("^app/", "");

        if (meaningfulPath.isEmpty()) {
            return componentName;
        }
        return meaningfulPath + "/" + componentName;
    }

    /**
     * Extracts the component class name from the file path.
     *
     * Converts kebab-case filename to PascalCase class name.
     * Example: "task-list.component.ts" -> "TaskListComponent"
     *
     * @param path Path to the component file
     * @return Component class name
     */
    private String extractComponentName(Path path) {
        String fileName = path.getFileName().toString();
        String baseName = fileName.replace(".component.ts", "");

        // Convert kebab-case to PascalCase
        return Arrays.stream(baseName.split("-"))
                .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1))
                .collect(Collectors.joining()) + "Component";
    }
}
