package com.agentivy.backend.tools.harness.deployer;

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
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * Deploys generated harness component to Angular project.
 */
@Slf4j
@Component
public class HarnessDeployerTool implements ToolProvider {

    private static final String HARNESS_DIR = "src/app/agent-ivy-harness";
    private static final String HARNESS_FILE = "harness.component.ts";

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "harness.deploy",
            "Harness Deployer",
            "Deploys generated harness component to Angular project",
            ToolCategory.COMPONENT_TESTING,
            "1.0.0",
            true,
            List.of("angular", "deploy", "harness", "file-write"),
            Map.of(
                "harnessDirectory", HARNESS_DIR,
                "harnessFile", HARNESS_FILE
            )
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(FunctionTool.create(this, "deployHarness"));
    }

    public Maybe<ImmutableMap<String, Object>> deployHarness(
            @Schema(name = "repoPath") String repoPath,
            @Schema(name = "harnessCode") String harnessCode) {

        return Maybe.fromCallable(() -> {
            try {
                Path repoRoot = Path.of(repoPath);
                Path harnessDir = repoRoot.resolve(HARNESS_DIR);
                Path harnessFilePath = harnessDir.resolve(HARNESS_FILE);

                // Create directory if needed
                if (!Files.exists(harnessDir)) {
                    Files.createDirectories(harnessDir);
                    log.info("Created harness directory: {}", harnessDir);
                }

                // Write harness file
                Files.writeString(harnessFilePath, harnessCode,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

                log.info("Deployed harness to: {}", harnessFilePath);

                return ImmutableMap.<String, Object>builder()
                    .put("status", "success")
                    .put("harnessFilePath", harnessFilePath.toString())
                    .put("harnessUrl", "http://localhost:4200/agent-ivy-harness")
                    .put("routeConfig", generateRouteConfig())
                    .build();

            } catch (Exception e) {
                log.error("Failed to deploy harness", e);
                return ImmutableMap.of(
                    "status", "error",
                    "message", e.getMessage()
                );
            }
        });
    }

    private String generateRouteConfig() {
        return "{ path: 'agent-ivy-harness', loadComponent: () => import('./agent-ivy-harness/harness.component').then(m => m.HarnessComponent) }";
    }
}
