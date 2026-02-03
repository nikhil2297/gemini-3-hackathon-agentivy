package com.agentivy.backend.tools.github;

import com.agentivy.backend.service.EventPublisherHelper;
import com.agentivy.backend.tools.github.support.AngularComponentDetector;
import com.agentivy.backend.tools.registry.ToolCategory;
import com.agentivy.backend.tools.registry.ToolMetadata;
import com.agentivy.backend.tools.registry.ToolProvider;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Maybe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub Component Scanner Tool - Atomic operation for discovering Angular components.
 *
 * This tool does ONE thing: scan a repository for Angular components.
 * Following IntelliJ's atomic operation pattern for maximum reusability.
 *
 * WORKFLOW:
 * 1. GitHubCloneTool.cloneRepository() → returns localPath
 * 2. scanForAngularComponents(localPath) → returns component list
 * 3. Use component paths with GitHubFileReaderTool to read files
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubComponentScannerTool implements ToolProvider {

    private final AngularComponentDetector componentDetector;
    private final EventPublisherHelper eventPublisher;

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "github.scanComponents",
            "Scan for Angular Components",
            "Scans a repository for Angular components and returns their file paths",
            ToolCategory.CODE_ANALYSIS,
            "1.0.0",
            true,
            List.of("angular", "component", "scan", "analysis"),
            Map.of()
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(FunctionTool.create(this, "scanForAngularComponents"));
    }

    /**
     * Scans a cloned repository for Angular components.
     *
     * WHEN TO USE:
     * - After cloning a repository
     * - To discover all components that can be tested
     *
     * RETURNS:
     * - List of components with name, paths to TS/HTML/CSS files
     *
     * @param localPath Local filesystem path to the cloned repository
     * @return Map with status, components list, count, and message
     */
    public Maybe<ImmutableMap<String, Object>> scanForAngularComponents(
            @Schema(
                name = "localPath",
                description = "Local filesystem path to the cloned repository. " +
                             "Use 'localPath' from cloneRepository output. " +
                             "Example: 'C:/Temp/agentivy/my-app-1704567890123'"
            )
            String localPath) {

        return Maybe.fromCallable(() -> {
            log.info("Scanning for Angular components in: {}", localPath);
            long startTime = System.currentTimeMillis();

            eventPublisher.publishToolCall("scanForAngularComponents", "Scanning repository for Angular components: " + localPath);

            // Publish starting status
            eventPublisher.publishComponentStatus(
                "ComponentScanner",
                "scanner",
                "starting",
                "Initializing repository scan...",
                Map.of(
                    "repositoryPath", localPath,
                    "scanPatterns", List.of("**/*.component.ts", "**/*.ts"),
                    "targetFramework", "Angular"
                )
            );

            try {
                Path rootPath = Path.of(localPath);

                if (!Files.exists(rootPath)) {
                    eventPublisher.publishComponentStatus(
                        "ComponentScanner",
                        "scanner",
                        "failed",
                        "Repository path does not exist",
                        Map.of(
                            "error", "Local path does not exist: " + localPath
                        )
                    );
                    return ImmutableMap.<String, Object>of(
                        "status", "error",
                        "message", "Local path does not exist: " + localPath
                    );
                }

                eventPublisher.publishComponentStatus(
                    "ComponentScanner",
                    "scanner",
                    "in-progress",
                    "Scanning repository for Angular components...",
                    Map.of(
                        "phase", "scanning",
                        "progressPercent", 30
                    )
                );

                // Scan for components
                List<AngularComponentDetector.ComponentInfo> components =
                    componentDetector.findComponents(rootPath);

                eventPublisher.publishComponentStatus(
                    "ComponentScanner",
                    "scanner",
                    "in-progress",
                    "Processing component metadata...",
                    Map.of(
                        "phase", "processing",
                        "componentsFound", components.size(),
                        "progressPercent", 70
                    )
                );

                // Convert to output format and collect metadata
                List<Map<String, String>> componentMaps = components.stream()
                    .map(this::toMap)
                    .toList();

                // Collect statistics
                long componentsWithTemplates = components.stream()
                    .filter(c -> c.htmlPath().isPresent())
                    .count();
                long componentsWithStyles = components.stream()
                    .filter(c -> c.cssPath().isPresent())
                    .count();

                long timeElapsed = System.currentTimeMillis() - startTime;

                // Publish completion status
                eventPublisher.publishComponentStatus(
                    "ComponentScanner",
                    "scanner",
                    "completed",
                    "Found " + components.size() + " component(s)",
                    Map.of(
                        "passed", true,
                        "totalComponents", components.size(),
                        "componentsWithTemplates", componentsWithTemplates,
                        "componentsWithStyles", componentsWithStyles,
                        "componentsWithBoth", components.stream()
                            .filter(c -> c.htmlPath().isPresent() && c.cssPath().isPresent())
                            .count(),
                        "componentNames", components.stream()
                            .map(AngularComponentDetector.ComponentInfo::name)
                            .toList(),
                        "timeElapsed", timeElapsed + "ms"
                    )
                );

                return ImmutableMap.<String, Object>of(
                    "status", "success",
                    "components", componentMaps,
                    "count", components.size(),
                    "message", "Found " + components.size() + " Angular components. Use readComponentFiles to analyze each."
                );

            } catch (Exception e) {
                log.error("Failed to scan for Angular components: {}", e.getMessage(), e);

                eventPublisher.publishComponentStatus(
                    "ComponentScanner",
                    "scanner",
                    "failed",
                    "Scan failed: " + e.getMessage(),
                    Map.of(
                        "error", e.getMessage(),
                        "errorType", e.getClass().getSimpleName()
                    )
                );

                return ImmutableMap.<String, Object>of(
                    "status", "error",
                    "message", "Failed to scan: " + e.getMessage()
                );
            }
        });
    }

    /**
     * Converts ComponentInfo to a Map for JSON serialization.
     */
    private Map<String, String> toMap(AngularComponentDetector.ComponentInfo info) {
        Map<String, String> map = new HashMap<>();
        map.put("name", info.name());
        map.put("fullName", info.fullName());
        map.put("relativePath", info.relativePath());
        map.put("tsPath", info.tsPath());

        info.htmlPath().ifPresent(p -> map.put("htmlPath", p));
        info.cssPath().ifPresent(p -> map.put("cssPath", p));

        return map;
    }
}
