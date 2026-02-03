package com.agentivy.backend.tools.harness;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class HarnessCodeGenerator {

    public record ServiceMockDef(String name, String method, String jsonValue, boolean isObservable) {}
    public record VariableDef(String name, String value) {}
    public record ImportDef(String className, String path) {}

    public String generate(
            String componentName,
            String componentSelector,
            String componentInputs,
            String uniqueId,
            List<ImportDef> imports,
            List<VariableDef> variables,
            List<ServiceMockDef> serviceMocks
    ) {
        String importsSection = buildImports(imports, serviceMocks);
        String globalVars = buildGlobalVars(variables);
        String mockClasses = buildMockClasses(serviceMocks);
        String decorator = buildDecorator(componentName, componentSelector, componentInputs, serviceMocks, uniqueId);
        String classBody = buildClassBody(variables);

        return String.join("\n\n", importsSection, globalVars, mockClasses, decorator, classBody);
    }

    private String buildImports(List<ImportDef> imports, List<ServiceMockDef> mocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("import { Component } from '@angular/core';\n");
        sb.append("import { CommonModule } from '@angular/common';\n");

        if (mocks.stream().anyMatch(ServiceMockDef::isObservable)) {
            sb.append("import { of } from 'rxjs';\n");
        }

        for (ImportDef imp : imports) {
            sb.append("import { ").append(imp.className()).append(" } from '").append(imp.path()).append("';\n");
        }
        return sb.toString();
    }

    private String buildGlobalVars(List<VariableDef> vars) {
        if (vars.isEmpty()) return "";
        return "// ===== GLOBAL MOCKS =====\n" +
                vars.stream()
                        .map(v -> "const " + v.name() + " = " + v.value() + ";")
                        .collect(Collectors.joining("\n"));
    }

    private String buildMockClasses(List<ServiceMockDef> mocks) {
        // Group by service name
        var grouped = mocks.stream().collect(Collectors.groupingBy(ServiceMockDef::name));

        return grouped.entrySet().stream().map(entry -> {
            String serviceName = entry.getKey();
            StringBuilder cls = new StringBuilder("class Mock" + serviceName + " {\n");

            for (ServiceMockDef m : entry.getValue()) {
                String val = m.isObservable() ? "of(" + m.jsonValue() + ")" : m.jsonValue();
                // Heuristic: if it looks like a method call (args), mock as function, else property
                if (m.method().endsWith("$") || !m.jsonValue().contains("return")) {
                    cls.append("  ").append(m.method()).append(" = ").append(val).append(";\n");
                } else {
                    cls.append("  ").append(m.method()).append("(...args: any[]) { return ").append(val).append("; }\n");
                }
            }
            cls.append("}");
            return cls.toString();
        }).collect(Collectors.joining("\n\n"));
    }

    private String buildDecorator(String name, String selector, String inputs, List<ServiceMockDef> mocks, String id) {
        String providers = mocks.stream()
                .map(ServiceMockDef::name)
                .distinct()
                .map(s -> String.format("{ provide: %s, useClass: Mock%s }", s, s))
                .collect(Collectors.joining(",\n    "));

        return """
            @Component({
              selector: 'app-harness',
              standalone: true,
              imports: [CommonModule, %s],
              providers: [
                %s
              ],
              template: `
                <div id="%s" style="padding: 20px;">
                  <%s %s>
                  </%s>
                </div>
              `
            })
            """.formatted(name, providers, id, selector, inputs, selector);
    }

    private String buildClassBody(List<VariableDef> vars) {
        String properties = vars.stream()
                .map(v -> "  " + v.name() + " = " + v.name() + ";")
                .collect(Collectors.joining("\n"));

        return """
            export class HarnessComponent {
            %s
              handleEvent(n: string, p: any) { console.log('[Harness]', n, p); }
            }
            """.formatted(properties);
    }
}