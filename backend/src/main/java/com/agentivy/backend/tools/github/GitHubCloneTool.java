package com.agentivy.backend.tools.github;

import com.agentivy.backend.tools.github.model.ClassifiedComponent;
import com.agentivy.backend.tools.github.model.ComponentType;
import com.agentivy.backend.tools.github.support.AngularComponentDetector;
import com.agentivy.backend.tools.github.support.ComponentClassifier;
import com.agentivy.backend.tools.github.support.GitRepositoryManager;
import com.agentivy.backend.tools.github.support.GitHubUrlParser;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitHub Clone Tool - Atomic operation for cloning repositories.
 *
 * This tool does ONE thing: clone a GitHub repository to local filesystem.
 * Following IntelliJ's atomic operation pattern for maximum reusability.
 *
 * WORKFLOW:
 * 1. cloneRepository(repoUrl) → returns localPath
 * 2. Use localPath with GitHubComponentScannerTool to find components
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubCloneTool implements ToolProvider {

    private final GitRepositoryManager repositoryManager;
    private final GitHubUrlParser urlParser;
    private final AngularComponentDetector componentDetector;
    private final ComponentClassifier componentClassifier;

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "github.clone",
            "Clone GitHub Repository",
            "Clones a GitHub repository to local filesystem for analysis",
            ToolCategory.SOURCE_CONTROL,
            "1.0.0",
            true,
            List.of("git", "github", "repository", "clone"),
            Map.of(
                "workDirectory", repositoryManager.getWorkDirectory().toString()
            )
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(FunctionTool.create(this, "cloneRepository"));
    }

    /**
     * Clones a GitHub repository to local filesystem.
     *
     * WHEN TO USE:
     * - First step when analyzing a new repository
     * - Returns localPath needed for subsequent operations
     *
     * @param repoUrl GitHub repository URL (HTTPS or SSH format)
     * @return Map with status, localPath, repoName, and message
     */
    public Maybe<ImmutableMap<String, Object>> cloneRepository(
            @Schema(
                name = "repoUrl",
                description = "GitHub repository URL to clone. " +
                             "Supports both HTTPS (https://github.com/user/repo) and " +
                             "SSH (git@github.com:user/repo.git) formats."
            )
            String repoUrl) {

        return Maybe.fromCallable(() -> {
            log.info("Cloning repository: {}", repoUrl);

            try {
                // Validate URL
                if (!urlParser.isValidGitHubUrl(repoUrl)) {
                    return ImmutableMap.<String, Object>of(
                        "status", "error",
                        "message", "Invalid GitHub URL format: " + repoUrl
                    );
                }

                // Extract repository name
                String repoName = urlParser.extractRepoName(repoUrl);

                // Normalize URL to HTTPS
                String normalizedUrl = urlParser.normalizeUrl(repoUrl);

                // Clone repository
                Path localPath = repositoryManager.clone(normalizedUrl, repoName);

                log.info("Repository cloned successfully to: {}", localPath);

                // Discover and classify components
                List<ClassifiedComponent> classifiedComponents = discoverAndClassifyComponents(localPath);
                Map<String, List<ClassifiedComponent>> componentsByType = componentClassifier.groupByType(classifiedComponents);
                Map<String, Integer> summary = componentClassifier.calculateSummary(classifiedComponents);

                String message = String.format(
                    "Repository cloned successfully with %d components discovered (%d pages, %d components, %d elements).",
                    summary.get("total"),
                    summary.get("pages"),
                    summary.get("components"),
                    summary.get("elements")
                );

                return ImmutableMap.<String, Object>of(
                    "status", "success",
                    "localPath", localPath.toString(),
                    "repoName", repoName,
                    "message", message,
                    "componentsSummary", summary,
                    "componentsByType", componentsByType
                );

            } catch (Exception e) {
                log.error("Error cloning repository: {}", e.getMessage(), e);
                return ImmutableMap.<String, Object>of(
                    "status", "error",
                    "message", "Failed to clone repository: " + e.getMessage()
                );
            }
        });
    }

    /**
     * Discovers all Angular components in the repository and classifies them by type.
     *
     * @param rootPath Root directory of the cloned repository
     * @return List of classified components
     */
    private List<ClassifiedComponent> discoverAndClassifyComponents(Path rootPath) {
        try {
            List<AngularComponentDetector.ComponentInfo> components = componentDetector.findComponents(rootPath);
            List<ClassifiedComponent> classifiedComponents = new ArrayList<>();

            for (AngularComponentDetector.ComponentInfo component : components) {
                // Use the relative tsPath to classify component
                ComponentType type = componentClassifier.classifyComponent(Path.of(component.tsPath()));

                ClassifiedComponent classified = new ClassifiedComponent(
                    component.name(),
                    component.tsPath(),
                    component.htmlPath().orElse(null),
                    component.cssPath().orElse(null),
                    type,
                    component.relativePath()
                );

                classifiedComponents.add(classified);
            }

            log.info("Classified {} components from {}", classifiedComponents.size(), rootPath);
            return classifiedComponents;

        } catch (Exception e) {
            log.error("Error discovering components: {}", e.getMessage(), e);
            return List.of();
        }
    }
}
