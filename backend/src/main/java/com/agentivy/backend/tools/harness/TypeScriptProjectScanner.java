package com.agentivy.backend.tools.harness;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class TypeScriptProjectScanner {

    // Matches: export class/interface/type/const Name
    private static final String EXPORT_REGEX = "export\\s+(?:abstract\\s+)?(class|interface|enum|type|const|let|var)\\s+%s\\b";

    public Optional<Path> findFileDefiningType(Path searchRoot, String typeName) {
        if (!Files.exists(searchRoot)) return Optional.empty();

        Pattern specificPattern = Pattern.compile(String.format(EXPORT_REGEX, Pattern.quote(typeName)));
        String kebabName = toKebabCase(typeName);

        try (Stream<Path> stream = Files.walk(searchRoot)) {
            // Filter relevant files first to reduce IO
            var candidates = stream
                    .filter(p -> p.toString().endsWith(".ts"))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().endsWith(".spec.ts"))
                    .toList();

            // Pass 1: Check filenames (Fastest)
            // If looking for 'TaskListComponent', check 'task-list.component.ts'
            for (Path p : candidates) {
                String filename = p.getFileName().toString();
                if (filename.startsWith(kebabName + ".")) {
                    if (fileContainsExport(p, specificPattern)) return Optional.of(p);
                }
            }

            // Pass 2: Deep scan all files (Slower, fallback)
            for (Path p : candidates) {
                if (fileContainsExport(p, specificPattern)) return Optional.of(p);
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public String calculateImportPath(Path fromDir, Path toFile) {
        String pathStr = fromDir.relativize(toFile).toString().replace("\\", "/");
        if (pathStr.endsWith(".ts")) pathStr = pathStr.substring(0, pathStr.length() - 3);
        if (!pathStr.startsWith(".")) pathStr = "./" + pathStr;
        return pathStr;
    }

    private boolean fileContainsExport(Path path, Pattern pattern) {
        try {
            String content = Files.readString(path);
            return pattern.matcher(content).find();
        } catch (IOException e) {
            return false;
        }
    }

    private String toKebabCase(String input) {
        return input.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }
}
