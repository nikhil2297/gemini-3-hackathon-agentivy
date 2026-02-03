package com.agentivy.backend.tools.harness;

import com.agentivy.backend.tools.registry.ToolCategory;
import com.agentivy.backend.tools.registry.ToolMetadata;
import com.agentivy.backend.tools.registry.ToolProvider;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Component Scaffold Tool for Angular Component Isolation Testing.
 *
 * Generates a 'harness.component.ts' file that acts as a standalone wrapper
 * for testing a specific Angular component with mocked dependencies.
 *
 * @deprecated Use the new atomic tools instead:
 * - {@link com.agentivy.backend.tools.harness.metadata.ComponentMetadataExtractorTool}
 * - {@link com.agentivy.backend.tools.harness.generator.HarnessCodeGeneratorTool}
 * - {@link com.agentivy.backend.tools.harness.validator.TypeScriptValidatorTool}
 * - {@link com.agentivy.backend.tools.harness.deployer.HarnessDeployerTool}
 * This class will be removed in version 2.0.0
 */
@Deprecated(since = "1.1.0", forRemoval = true)
@Slf4j
@Component
public class ComponentScaffoldTool implements ToolProvider {

    private static final String HARNESS_DIR_REL = "src/app/agent-ivy-harness";
    private static final String HARNESS_FILE = "harness.component.ts";

    // Regex to find 'export class/interface/type/enum/const Name'
    // Handles: "export class X", "export abstract class X", "export interface X", "export const X"
    private static final String EXPORT_REGEX_TEMPLATE = "export\\s+(?:abstract\\s+)?(class|interface|enum|type|const|let|var)\\s+%s\\b";

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "harness.scaffold",
            "Component Scaffold for Testing",
            "Creates isolated test harness for Angular components with mocked dependencies",
            ToolCategory.COMPONENT_TESTING,
            "1.0.0",
            true,
            List.of("angular", "testing", "harness", "component", "mock", "scaffold"),
            Map.of(
                "harnessDirectory", HARNESS_DIR_REL,
                "harnessFile", HARNESS_FILE
            )
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(
                FunctionTool.create(this, "loadComponentForTesting")
        );
    }

    /**
     * Creates a test harness to load an Angular component with mock data and services.
     */
    public Maybe<ImmutableMap<String, Object>> loadComponentForTesting(
            @Schema(name = "repoPath", description = "Absolute filesystem path to Angular repo root.")
            String repoPath,

            @Schema(name = "componentClassName", description = "TypeScript class name (e.g., 'TaskListComponent').")
            String componentClassName,

            @Schema(name = "componentImportPath", description = "Relative import path. If unsure, leave empty to let system resolve it.")
            String componentImportPath,

            @Schema(name = "componentSelector", description = "CSS selector (e.g., 'app-task-list').")
            String componentSelector,

            @Schema(name = "componentInputs", description = "Angular bindings (e.g., '[userId]=\"mockUserId\"').")
            String componentInputs,

            @Schema(name = "mockData", description = "Mock data definitions (e.g., 'mockUserId = 1; mockTasks = [...]').")
            String mockData,

            @Schema(name = "serviceMocks", description = "Service mocks config (e.g., 'TaskService|getTasks|[...]|true').")
            String serviceMocks,

            @Schema(name = "serviceImportPaths", description = "Optional import overrides. System will auto-resolve if empty.")
            String serviceImportPaths,

            @Schema(name = "additionalImports", description = "Extra imports (e.g., '{ Task } from \"../models/task\"').")
            String additionalImports) {

        return Maybe.fromCallable(() -> {
            log.info("Creating test harness for: {}", componentClassName);

            // Set current component in session context so other tools can use it
            com.agentivy.backend.service.SessionContext.setCurrentComponent(componentClassName);

            // 1. Validation
            if (isBlank(repoPath)) return errorResponse("repoPath is required");
            if (isBlank(componentClassName)) return errorResponse("componentClassName is required");
            if (isBlank(componentSelector)) return errorResponse("componentSelector is required");

            Path repoRoot = Paths.get(repoPath);
            Path harnessDir = repoRoot.resolve(HARNESS_DIR_REL);

            // 2. Normalize Inputs
            String inputs = normalize(componentInputs);
            String mocks = normalize(mockData);
            String services = normalize(serviceMocks);
            String servicePaths = normalize(serviceImportPaths);
            String extraImports = normalize(additionalImports);

            // 3. Setup Directory
            if (!Files.exists(harnessDir)) {
                Files.createDirectories(harnessDir);
            }

            // 4. Auto-Resolve Component Path
            String resolvedComponentImport = resolveImportPath(repoRoot, harnessDir, componentClassName, componentImportPath);

            String uniqueTestId = "test-wrapper-" + System.currentTimeMillis();

            // 5. Parse Configuration
            List<ServiceMock> parsedServiceMocks = parseServiceMocks(services);
            java.util.Map<String, String> serviceImportMap = parseServiceImportPaths(servicePaths);
            List<MockVariable> parsedVariables = parseMockVariables(mocks);

            // 6. Generate Code
            String harnessCode = generateHarnessCode(
                    repoRoot,
                    harnessDir,
                    componentClassName,
                    resolvedComponentImport,
                    componentSelector,
                    inputs,
                    parsedVariables,
                    parsedServiceMocks,
                    serviceImportMap,
                    extraImports,
                    uniqueTestId
            );

            // 7. Write File (Truncate/Overwrite existing)
            Path harnessFilePath = harnessDir.resolve(HARNESS_FILE);
            Files.writeString(harnessFilePath, harnessCode,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            log.info("Harness created at: {}", harnessFilePath);

            return ImmutableMap.<String, Object>builder()
                    .put("status", "success")
                    .put("harnessFilePath", harnessFilePath.toString())
                    .put("resolvedComponentPath", resolvedComponentImport)
                    .put("uniqueTestId", uniqueTestId)
                    .put("routeConfigToAdd", generateRouteConfig())
                    .build();
        });
    }

    // =========================================================================
    // CODE GENERATION LOGIC
    // =========================================================================

    private String generateHarnessCode(
            Path repoRoot,
            Path harnessDir,
            String componentClassName,
            String componentImportPath,
            String componentSelector,
            String componentInputs,
            List<MockVariable> mockVariables,
            List<ServiceMock> serviceMocks,
            java.util.Map<String, String> serviceImportMap,
            String additionalImports,
            String uniqueTestId) {

        StringBuilder code = new StringBuilder();

        // 1. Imports Section
        code.append(generateImportsSection(repoRoot, harnessDir, componentClassName, componentImportPath, serviceMocks, serviceImportMap, additionalImports));

        // 2. Global Constants (Fixes scope issues for Services & Components)
        code.append(generateGlobalConstants(mockVariables));

        // 3. Mock Service Classes
        code.append(generateMockServicesSection(serviceMocks));

        // 4. Component Decorator
        code.append(generateComponentDecorator(componentClassName, componentSelector, componentInputs, serviceMocks, uniqueTestId));

        // 5. Component Class (Maps global constants to template)
        code.append(generateComponentClass(mockVariables));

        return code.toString();
    }

    private String generateImportsSection(
            Path repoRoot,
            Path harnessDir,
            String componentClassName,
            String componentImportPath,
            List<ServiceMock> serviceMocks,
            java.util.Map<String, String> serviceImportMap,
            String additionalImports) {

        StringBuilder imports = new StringBuilder();
        imports.append("import { Component } from '@angular/core';\n");
        imports.append("import { CommonModule } from '@angular/common';\n");

        boolean hasObservableMocks = serviceMocks.stream().anyMatch(ServiceMock::isObservable);
        if (hasObservableMocks) {
            imports.append("import { of } from 'rxjs';\n");
        }

        // Target Component Import
        imports.append("import { ").append(componentClassName).append(" } from '").append(componentImportPath).append("';\n");

        // Service Imports (Auto-Resolved)
        java.util.Set<String> importedServices = new java.util.HashSet<>();
        for (ServiceMock mock : serviceMocks) {
            if (importedServices.add(mock.serviceName())) {
                String className = mock.serviceName();
                String importPath;

                if (serviceImportMap.containsKey(className)) {
                    importPath = serviceImportMap.get(className);
                } else {
                    importPath = resolveImportPath(repoRoot, harnessDir, className, null);
                }

                imports.append("import { ").append(className).append(" } from '").append(importPath).append("';\n");
            }
        }

        // Additional Imports (Models/Interfaces) - Attempt to fix broken paths
        if (!additionalImports.isEmpty()) {
            additionalImports.lines()
                    .filter(line -> !line.isBlank())
                    .forEach(line -> {
                        String processedLine = fixImportPathIfBroken(repoRoot, harnessDir, line);
                        imports.append("import ").append(processedLine).append(";\n");
                    });
        }

        imports.append("\n");
        return imports.toString();
    }

    private String generateGlobalConstants(List<MockVariable> variables) {
        if (variables.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("// ===== GLOBAL MOCK DATA =====\n");
        sb.append("// Defined globally so MockServices can access them\n");
        for (MockVariable var : variables) {
            sb.append("const ").append(var.name).append(" = ").append(var.value).append(";\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private String generateMockServicesSection(List<ServiceMock> serviceMocks) {
        StringBuilder mocks = new StringBuilder();
        java.util.Map<String, java.util.List<ServiceMock>> mocksByService = new java.util.LinkedHashMap<>();

        for (ServiceMock mock : serviceMocks) {
            mocksByService.computeIfAbsent(mock.serviceName(), k -> new java.util.ArrayList<>()).add(mock);
        }

        for (java.util.List<ServiceMock> mocksList : mocksByService.values()) {
            if (!mocksList.isEmpty()) {
                mocks.append(generateMockServiceClass(mocksList)).append("\n");
            }
        }
        return mocks.toString();
    }

    private String generateMockServiceClass(java.util.List<ServiceMock> mocks) {
        if (mocks.isEmpty()) return "";

        String serviceName = mocks.get(0).serviceName();
        StringBuilder sb = new StringBuilder("class Mock").append(serviceName).append(" {\n");

        for (ServiceMock m : mocks) {
            boolean isProp = m.propertyOrMethod().endsWith("$") || !m.propertyOrMethod().matches("^[a-z].*");
            String value = m.mockDataJson();

            if (isProp) {
                sb.append("  ").append(m.propertyOrMethod()).append(" = ");
                if (m.isObservable()) sb.append("of(").append(value).append(")");
                else sb.append(value);
                sb.append(";\n");
            } else {
                sb.append("  ").append(m.propertyOrMethod()).append("(...args: any[]) {\n");
                sb.append("    return ");
                if (m.isObservable()) sb.append("of(").append(value).append(")");
                else sb.append(value);
                sb.append(";\n");
                sb.append("  }\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String generateComponentDecorator(String componentClassName, String componentSelector, String componentInputs, List<ServiceMock> serviceMocks, String uniqueTestId) {
        StringBuilder decorator = new StringBuilder();
        decorator.append("@Component({\n");
        decorator.append("  selector: 'app-harness',\n");
        decorator.append("  standalone: true,\n");
        decorator.append("  imports: [CommonModule, ").append(componentClassName).append("],\n");

        if (!serviceMocks.isEmpty()) {
            java.util.Set<String> providedServices = new java.util.LinkedHashSet<>();
            serviceMocks.forEach(m -> providedServices.add(m.serviceName()));

            decorator.append("  providers: [\n");
            int i = 0;
            for (String serviceName : providedServices) {
                decorator.append("    { provide: ").append(serviceName).append(", useClass: Mock").append(serviceName).append(" }");
                if (i++ < providedServices.size() - 1) decorator.append(",");
                decorator.append("\n");
            }
            decorator.append("  ],\n");
        }

        decorator.append("  template: `\n");
        decorator.append("    <div id=\"").append(uniqueTestId).append("\" style=\"padding: 20px;\">\n");
        decorator.append("      <").append(componentSelector);
        if (!componentInputs.isEmpty()) {
            decorator.append("\n        ").append(componentInputs);
        }
        decorator.append(">\n");
        decorator.append("      </").append(componentSelector).append(">\n");
        decorator.append("    </div>\n");
        decorator.append("  `\n");
        decorator.append("})\n");
        return decorator.toString();
    }

    private String generateComponentClass(List<MockVariable> variables) {
        StringBuilder classCode = new StringBuilder();
        classCode.append("export class HarnessComponent {\n");
        if (!variables.isEmpty()) {
            classCode.append("  // Expose global mocks to template bindings\n");
            for (MockVariable var : variables) {
                classCode.append("  ").append(var.name).append(" = ").append(var.name).append(";\n");
            }
        }
        classCode.append("\n  handleEvent(n: string, p: any) { console.log('[Harness]', n, p); }\n");
        classCode.append("}\n");
        return classCode.toString();
    }

    // =========================================================================
    // IMPORT RESOLUTION & PATH FINDING
    // =========================================================================

    private String resolveImportPath(Path repoRoot, Path harnessDir, String className, String defaultPath) {
        try {
            // Search 'src' (including shared, core, feature, etc)
            Optional<Path> definedFile = findFileDefiningClass(repoRoot.resolve("src"), className);
            if (definedFile.isPresent()) {
                return calculateRelativeImport(harnessDir, definedFile.get());
            }
        } catch (Exception e) {
            log.warn("Failed to resolve path for {}: {}", className, e.getMessage());
        }

        if (defaultPath != null && !defaultPath.isEmpty()) return defaultPath;
        return deriveServiceImportPath(className);
    }

    /**
     * Searches for a file defining the class/interface.
     * Strategy 1: Look for "export class ClassName" content.
     * Strategy 2: Look for filename "class-name.ts" or "class-name.model.ts" as fallback.
     */
    private Optional<Path> findFileDefiningClass(Path searchRoot, String className) throws IOException {
        if (!Files.exists(searchRoot)) return Optional.empty();

        // Expanded regex to catch 'abstract class', 'interface', 'const', 'type'
        Pattern exportPattern = Pattern.compile(String.format(EXPORT_REGEX_TEMPLATE, Pattern.quote(className)));

        try (Stream<Path> stream = Files.walk(searchRoot)) {
            List<Path> allTsFiles = stream
                    .filter(p -> p.toString().endsWith(".ts"))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().endsWith(".spec.ts"))
                    .toList(); // Collect to list to iterate twice if needed

            // Pass 1: Content Search (Precise)
            for (Path p : allTsFiles) {
                try {
                    String content = Files.readString(p);
                    Matcher m = exportPattern.matcher(content);
                    if (m.find()) return Optional.of(p);
                } catch (IOException e) { /* continue */ }
            }

            // Pass 2: Filename Fuzzy Search (Fallback)
            // e.g. Class "Task" -> matches "task.model.ts" or "task.ts"
            String kebabName = className.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
            for (Path p : allTsFiles) {
                String fileName = p.getFileName().toString();
                if (fileName.equals(kebabName + ".ts") ||
                        fileName.equals(kebabName + ".model.ts") ||
                        fileName.equals(kebabName + ".interface.ts")) {
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }

    private String calculateRelativeImport(Path fromDir, Path toFile) {
        Path relativePath = fromDir.relativize(toFile);
        String pathStr = relativePath.toString().replace("\\", "/");
        if (pathStr.endsWith(".ts")) pathStr = pathStr.substring(0, pathStr.length() - 3);
        if (!pathStr.startsWith(".")) pathStr = "./" + pathStr;
        return pathStr;
    }

    /**
     * Fixes imports like: import { Task } from "../../models/task.model"
     * If the path is wrong, it finds the real file and corrects it.
     */
    private String fixImportPathIfBroken(Path repoRoot, Path harnessDir, String importLine) {
        Pattern p = Pattern.compile("import\\s+\\{\\s*(\\w+)\\s*}\\s+from\\s+['\"]([^'\"]+)['\"]");
        Matcher m = p.matcher(importLine);

        if (m.find()) {
            String className = m.group(1);
            try {
                // Search src/ instead of src/app to catch shared models
                Optional<Path> realPath = findFileDefiningClass(repoRoot.resolve("src"), className);
                if (realPath.isPresent()) {
                    String newPath = calculateRelativeImport(harnessDir, realPath.get());
                    return "{ " + className + " } from '" + newPath + "'";
                }
            } catch (Exception e) { /* ignore */ }
        }
        // If resolution fails, we must strip "import " to return valid syntax,
        // effectively trusting the user's path (even if it might be wrong).
        return importLine.replace("import ", "");
    }

    // =========================================================================
    // PARSING HELPERS
    // =========================================================================

    private record ServiceMock(String serviceName, String propertyOrMethod, String mockDataJson, boolean isObservable) {}
    private record MockVariable(String name, String value) {}

    private List<ServiceMock> parseServiceMocks(String serviceMocks) {
        if (isBlank(serviceMocks)) return List.of();
        return serviceMocks.lines()
                .filter(l -> !l.isBlank())
                .map(line -> {
                    String[] parts = line.split("\\|");
                    if (parts.length >= 3) {
                        return new ServiceMock(parts[0].trim(), parts[1].trim(), parts[2].trim(),
                                parts.length < 4 || !"false".equalsIgnoreCase(parts[3].trim()));
                    }
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<MockVariable> parseMockVariables(String mockData) {
        if (isBlank(mockData)) return List.of();
        List<MockVariable> vars = new java.util.ArrayList<>();
        String[] statements = mockData.split(";");

        Pattern assignPattern = Pattern.compile("(?:export\\s+)?(?:const|let|var)?\\s*(\\w+)\\s*=\\s*(.*)", Pattern.DOTALL);

        for (String stmt : statements) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            Matcher m = assignPattern.matcher(trimmed);
            if (m.matches()) {
                vars.add(new MockVariable(m.group(1), m.group(2)));
            } else {
                log.warn("Skipping unparseable mock statement: {}", trimmed);
            }
        }
        return vars;
    }

    private java.util.Map<String, String> parseServiceImportPaths(String serviceImportPaths) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (!isBlank(serviceImportPaths)) {
            serviceImportPaths.lines().filter(l -> !l.isBlank()).forEach(l -> {
                String[] p = l.split("\\|", 2);
                if (p.length == 2) map.put(p[0].trim(), p[1].trim());
            });
        }
        return map;
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    private String deriveServiceImportPath(String serviceName) {
        String kebabCase = serviceName
                .replaceAll("Service$", "")
                .replaceAll("([a-z])([A-Z])", "$1-$2")
                .toLowerCase();
        return "../services/" + kebabCase + ".service";
    }

    private String generateRouteConfig() {
        return "// Add to app.routes.ts:\n{ path: 'agent-ivy-harness', loadComponent: () => import('./agent-ivy-harness/harness.component').then(m => m.HarnessComponent) }";
    }

    private boolean isBlank(String str) { return str == null || str.isBlank(); }
    private String normalize(String str) { return str == null ? "" : str.trim(); }
    private ImmutableMap<String, Object> errorResponse(String message) { return ImmutableMap.of("status", "error", "message", message); }
}