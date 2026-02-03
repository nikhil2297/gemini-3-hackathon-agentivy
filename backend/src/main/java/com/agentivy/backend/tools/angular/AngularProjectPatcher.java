package com.agentivy.backend.tools.angular;

import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AngularProjectPatcher {

    private static final String HARNESS_ROUTE = """
          {
            path: 'agent-ivy-harness',
            loadComponent: () => import('./agent-ivy-harness/harness.component').then(m => m.HarnessComponent)
          },
        """;

    public void injectHarnessRoute(Path projectPath) throws Exception {
        Path routesFile = findRoutesFile(projectPath);
        if (routesFile == null) throw new IllegalStateException("Could not find app.routes.ts");

        String content = Files.readString(routesFile);
        if (content.contains("agent-ivy-harness")) return;

        // Try standard route array
        Pattern pattern = Pattern.compile("(export\\s+const\\s+routes\\s*:\\s*Routes\\s*=\\s*\\[)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            insertAndSave(routesFile, content, matcher.end());
            return;
        }

        // Try simplified pattern
        matcher = Pattern.compile("(Routes\\s*=\\s*\\[)", Pattern.MULTILINE).matcher(content);
        if (matcher.find()) {
            insertAndSave(routesFile, content, matcher.end());
            return;
        }

        throw new IllegalStateException("Could not locate Routes array in " + routesFile.getFileName());
    }

    private void insertAndSave(Path file, String content, int index) throws Exception {
        String newContent = content.substring(0, index) +
                "\n  // AgentIvy Harness (auto-injected)\n" + HARNESS_ROUTE +
                content.substring(index);
        Files.writeString(file, newContent);
    }

    private Path findRoutesFile(Path projectPath) throws Exception {
        // Simplified search logic
        Path standard = projectPath.resolve("src/app/app.routes.ts");
        if (Files.exists(standard)) return standard;

        return Files.walk(projectPath.resolve("src"))
                .filter(p -> p.toString().endsWith(".routes.ts") || p.toString().endsWith("-routing.module.ts"))
                .findFirst().orElse(null);
    }
}