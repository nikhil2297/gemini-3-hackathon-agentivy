package com.agentivy.backend.tools.harness.metadata;

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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Extracts metadata from Angular components for harness generation.
 */
@Slf4j
@Component
public class ComponentMetadataExtractorTool implements ToolProvider {

    private static final Pattern INPUT_PATTERN = Pattern.compile("@Input\\(\\)\\s+(\\w+)(?::|\\s*=)");
    private static final Pattern OUTPUT_PATTERN = Pattern.compile("@Output\\(\\)\\s+(\\w+)\\s*=\\s*new\\s+EventEmitter");
    private static final Pattern CONSTRUCTOR_PARAM_PATTERN = Pattern.compile("(private|public|protected)\\s+(\\w+):\\s*(\\w+)");
    private static final Pattern SELECTOR_PATTERN = Pattern.compile("selector:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("import\\s+\\{([^}]+)\\}\\s+from\\s+['\"]([^'\"]+)['\"]");

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "harness.metadata.extract",
            "Component Metadata Extractor",
            "Extracts metadata from Angular components (inputs, outputs, dependencies)",
            ToolCategory.CODE_ANALYSIS,
            "1.0.0",
            true,
            List.of("angular", "metadata", "component", "analysis"),
            Map.of()
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(FunctionTool.create(this, "extractComponentMetadata"));
    }

    public Maybe<ImmutableMap<String, Object>> extractComponentMetadata(
            @Schema(name = "repoPath") String repoPath,
            @Schema(name = "componentClassName") String componentClassName) {

        return Maybe.fromCallable(() -> {
            Path repoRoot = Path.of(repoPath);
            Path srcPath = repoRoot.resolve("src");

            // Find component file
            Optional<Path> componentFile = findComponentFile(srcPath, componentClassName);
            if (componentFile.isEmpty()) {
                return ImmutableMap.of("status", "error", "message", "Component file not found: " + componentClassName);
            }

            Path file = componentFile.get();
            String content = Files.readString(file);

            // Extract metadata
            String selector = extractSelector(content);
            List<ComponentMetadata.ComponentInput> inputs = extractInputs(content);
            List<ComponentMetadata.ComponentOutput> outputs = extractOutputs(content);
            List<ComponentMetadata.ServiceDependency> dependencies = extractDependencies(content, repoRoot, file);
            Map<String, String> existingImports = extractImports(content);
            String importPath = calculateImportPath(repoRoot, file);

            ComponentMetadata metadata = new ComponentMetadata(
                componentClassName,
                selector,
                importPath,
                inputs,
                outputs,
                dependencies,
                existingImports
            );

            return ImmutableMap.<String, Object>builder()
                .put("status", "success")
                .put("className", metadata.className())
                .put("selector", metadata.selector())
                .put("importPath", metadata.importPath())
                .put("inputs", metadata.inputs())
                .put("outputs", metadata.outputs())
                .put("dependencies", metadata.dependencies())
                .put("existingImports", metadata.existingImports())
                .build();
        });
    }

    private Optional<Path> findComponentFile(Path srcPath, String className) {
        try (Stream<Path> stream = Files.walk(srcPath)) {
            Pattern classPattern = Pattern.compile("export\\s+class\\s+" + Pattern.quote(className) + "\\b");
            return stream
                .filter(p -> p.toString().endsWith(".component.ts"))
                .filter(p -> !p.toString().contains("node_modules"))
                .filter(p -> !p.toString().endsWith(".spec.ts"))
                .filter(p -> {
                    try {
                        String content = Files.readString(p);
                        return classPattern.matcher(content).find();
                    } catch (Exception e) {
                        return false;
                    }
                })
                .findFirst();
        } catch (Exception e) {
            log.error("Error finding component file", e);
            return Optional.empty();
        }
    }

    private String extractSelector(String content) {
        Matcher m = SELECTOR_PATTERN.matcher(content);
        return m.find() ? m.group(1) : "";
    }

    private List<ComponentMetadata.ComponentInput> extractInputs(String content) {
        List<ComponentMetadata.ComponentInput> inputs = new ArrayList<>();
        Matcher m = INPUT_PATTERN.matcher(content);
        while (m.find()) {
            inputs.add(new ComponentMetadata.ComponentInput(m.group(1), "any"));
        }
        return inputs;
    }

    private List<ComponentMetadata.ComponentOutput> extractOutputs(String content) {
        List<ComponentMetadata.ComponentOutput> outputs = new ArrayList<>();
        Matcher m = OUTPUT_PATTERN.matcher(content);
        while (m.find()) {
            outputs.add(new ComponentMetadata.ComponentOutput(m.group(1), "EventEmitter<any>"));
        }
        return outputs;
    }

    private List<ComponentMetadata.ServiceDependency> extractDependencies(String content, Path repoRoot, Path currentFile) {
        List<ComponentMetadata.ServiceDependency> deps = new ArrayList<>();
        Matcher m = CONSTRUCTOR_PARAM_PATTERN.matcher(content);

        while (m.find()) {
            String serviceName = m.group(3);
            if (serviceName.endsWith("Service") || Character.isUpperCase(serviceName.charAt(0))) {
                String importPath = findServiceImportPath(repoRoot, serviceName);
                deps.add(new ComponentMetadata.ServiceDependency(serviceName, importPath));
            }
        }
        return deps;
    }

    private Map<String, String> extractImports(String content) {
        Map<String, String> imports = new HashMap<>();
        Matcher m = IMPORT_PATTERN.matcher(content);
        while (m.find()) {
            String symbols = m.group(1);
            String path = m.group(2);
            for (String symbol : symbols.split(",")) {
                imports.put(symbol.trim(), path);
            }
        }
        return imports;
    }

    private String findServiceImportPath(Path repoRoot, String serviceName) {
        try (Stream<Path> stream = Files.walk(repoRoot.resolve("src"))) {
            Pattern servicePattern = Pattern.compile("export\\s+class\\s+" + Pattern.quote(serviceName) + "\\b");
            Optional<Path> serviceFile = stream
                .filter(p -> p.toString().endsWith(".ts"))
                .filter(p -> !p.toString().contains("node_modules"))
                .filter(p -> !p.toString().endsWith(".spec.ts"))
                .filter(p -> {
                    try {
                        String content = Files.readString(p);
                        return servicePattern.matcher(content).find();
                    } catch (Exception e) {
                        return false;
                    }
                })
                .findFirst();

            if (serviceFile.isPresent()) {
                return calculateImportPath(repoRoot, serviceFile.get());
            }
        } catch (Exception e) {
            log.warn("Could not find service: {}", serviceName);
        }

        // Fallback
        String kebab = serviceName.replaceAll("Service$", "")
            .replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
        return "./services/" + kebab + ".service";
    }

    private String calculateImportPath(Path repoRoot, Path file) {
        // Harness is located at: src/app/agent-ivy-harness/
        Path harnessDir = repoRoot.resolve("src/app/agent-ivy-harness");

        // Calculate relative path from harness directory to the target file
        Path relativePath = harnessDir.relativize(file);
        String pathStr = relativePath.toString().replace("\\", "/");

        if (pathStr.endsWith(".ts")) {
            pathStr = pathStr.substring(0, pathStr.length() - 3);
        }

        // Ensure path starts with ./ or ../
        if (!pathStr.startsWith(".")) {
            pathStr = "./" + pathStr;
        }

        return pathStr;
    }
}
