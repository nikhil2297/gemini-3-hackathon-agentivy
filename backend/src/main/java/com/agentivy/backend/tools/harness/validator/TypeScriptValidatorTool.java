package com.agentivy.backend.tools.harness.validator;

import com.agentivy.backend.tools.registry.ToolCategory;
import com.agentivy.backend.tools.registry.ToolMetadata;
import com.agentivy.backend.tools.registry.ToolProvider;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Validates TypeScript code syntax using tsc compiler.
 */
@Slf4j
@Component
public class TypeScriptValidatorTool implements ToolProvider {

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "harness.typescript.validate",
            "TypeScript Validator",
            "Validates TypeScript code syntax using tsc",
            ToolCategory.CODE_ANALYSIS,
            "1.0.0",
            true,
            List.of("typescript", "validation", "compiler", "syntax"),
            Map.of()
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(FunctionTool.create(this, "validateTypeScript"));
    }

    public Maybe<ImmutableMap<String, Object>> validateTypeScript(
            @Schema(name = "code") String code,
            @Schema(name = "filePath") String filePath) {

        return Maybe.fromCallable(() -> {
            Path tempFile = null;
            try {
                // Write code to temp file
                tempFile = Files.createTempFile("harness-validate-", ".ts");
                Files.writeString(tempFile, code);

                // Run tsc --noEmit to validate syntax
                ProcessBuilder pb = new ProcessBuilder(
                    "npx", "tsc",
                    "--noEmit",
                    "--skipLibCheck",
                    "--lib", "ES2022,DOM",
                    "--target", "ES2022",
                    "--module", "ES2022",
                    tempFile.toString()
                );

                pb.redirectErrorStream(true);
                Process process = pb.start();

                // Capture output
                List<String> errors = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) {
                            errors.add(line);
                        }
                    }
                }

                boolean completed = process.waitFor(30, TimeUnit.SECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    return ImmutableMap.of("status", "error", "message", "Validation timeout");
                }

                int exitCode = process.exitValue();
                boolean valid = exitCode == 0 && errors.isEmpty();

                return ImmutableMap.<String, Object>builder()
                    .put("status", "success")
                    .put("valid", valid)
                    .put("errors", errors)
                    .put("exitCode", exitCode)
                    .build();

            } catch (Exception e) {
                log.error("TypeScript validation failed", e);
                return ImmutableMap.of(
                    "status", "error",
                    "message", e.getMessage(),
                    "valid", false
                );
            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (Exception ignored) {}
                }
            }
        });
    }
}
