package com.agentivy.backend.tools.github;

import com.agentivy.backend.tools.registry.ToolCategory;
import com.agentivy.backend.tools.registry.ToolMetadata;
import com.agentivy.backend.tools.registry.ToolProvider;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * GitHub File Reader Tool - Atomic operation for reading component files.
 *
 * This tool does ONE thing: read TypeScript, HTML, and CSS files for a component.
 * Following IntelliJ's atomic operation pattern for maximum reusability.
 *
 * WORKFLOW:
 * 1. GitHubCloneTool.cloneRepository() → returns localPath
 * 2. GitHubComponentScannerTool.scanForAngularComponents() → returns component paths
 * 3. readComponentFiles(typescriptPath, templatePath, stylesPath) → returns file contents
 * 4. Use contents with ComponentScaffoldTool.loadComponentForTesting()
 */
@Slf4j
@Component
public class GitHubFileReaderTool implements ToolProvider {

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "github.readFiles",
            "Read Component Files",
            "Reads TypeScript, HTML, and CSS files for an Angular component",
            ToolCategory.CODE_ANALYSIS,
            "1.0.0",
            true,
            List.of("file", "read", "component", "source", "angular"),
            Map.of()
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(FunctionTool.create(this, "readComponentFiles"));
    }

    /**
     * Reads the content of a component's files.
     *
     * WHEN TO USE:
     * - After scanning to analyze a specific component
     * - To extract @Input() decorators, constructor services, imports
     *
     * ANALYSIS TIPS:
     * From the TypeScript content, look for:
     * - @Input() properties → need mock data in componentInputs
     * - constructor(private service: ServiceName) → need serviceMocks
     * - import { Interface } from → may need additionalImports
     *
     * @param typescriptPath Absolute path to the .component.ts file
     * @param templatePath Absolute path to the .component.html file (optional)
     * @param stylesPath Absolute path to the .component.scss/.css file (optional)
     * @return Map with status and file contents
     */
    public Maybe<ImmutableMap<String, Object>> readComponentFiles(
            @Schema(
                name = "typescriptPath",
                description = "Absolute path to the .component.ts file. " +
                             "Use 'typescriptPath' from scanForAngularComponents output."
            )
            String typescriptPath,

            @Schema(
                name = "templatePath",
                description = "Absolute path to the .component.html file (optional). " +
                             "Use 'templatePath' from scanForAngularComponents output. " +
                             "Pass empty string if not available."
            )
            String templatePath,

            @Schema(
                name = "stylesPath",
                description = "Absolute path to the .component.scss/.css file (optional). " +
                             "Use 'stylesPath' from scanForAngularComponents output. " +
                             "Pass empty string if not available."
            )
            String stylesPath) {

        return Maybe.fromCallable(() -> {
            log.info("Reading component files: {}", typescriptPath);

            try {
                ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
                builder.put("status", "success");

                // Read TypeScript (required)
                Path tsPath = Path.of(typescriptPath);
                if (Files.exists(tsPath)) {
                    String tsContent = Files.readString(tsPath);
                    builder.put("typescript", tsContent);
                } else {
                    return ImmutableMap.<String, Object>of(
                        "status", "error",
                        "message", "TypeScript file not found: " + typescriptPath
                    );
                }

                // Read Template (optional)
                if (templatePath != null && !templatePath.isBlank()) {
                    Path htmlPath = Path.of(templatePath);
                    if (Files.exists(htmlPath)) {
                        String templateContent = Files.readString(htmlPath);
                        builder.put("template", templateContent);
                    }
                }

                // Read Styles (optional)
                if (stylesPath != null && !stylesPath.isBlank()) {
                    Path cssPath = Path.of(stylesPath);
                    if (Files.exists(cssPath)) {
                        String stylesContent = Files.readString(cssPath);
                        builder.put("styles", stylesContent);
                    }
                }

                builder.put("message", "Files read successfully. Analyze typescript for @Input() and constructor services.");

                return builder.build();

            } catch (Exception e) {
                log.error("Error reading component files: {}", e.getMessage(), e);
                return ImmutableMap.<String, Object>of(
                    "status", "error",
                    "message", "Failed to read files: " + e.getMessage()
                );
            }
        });
    }
}
