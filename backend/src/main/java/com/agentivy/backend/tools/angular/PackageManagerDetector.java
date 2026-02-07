package com.agentivy.backend.tools.angular;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects the package manager used by an Angular project.
 *
 * Detection strategy:
 * 1. Check for lock files (yarn.lock, pnpm-lock.yaml, package-lock.json, bun.lockb)
 * 2. Check for packageManager field in package.json
 * 3. Fall back to npm as default
 */
@Slf4j
@Component
public class PackageManagerDetector {

    public enum PackageManager {
        NPM("npm", "npx", "npm install", "npm run"),
        YARN("yarn", "yarn", "yarn install", "yarn"),
        PNPM("pnpm", "pnpm", "pnpm install", "pnpm"),
        BUN("bun", "bunx", "bun install", "bun run");

        private final String command;
        private final String execCommand;  // npx, yarn, pnpm, bunx
        private final String installCommand;
        private final String runCommand;

        PackageManager(String command, String execCommand, String installCommand, String runCommand) {
            this.command = command;
            this.execCommand = execCommand;
            this.installCommand = installCommand;
            this.runCommand = runCommand;
        }

        public String getCommand() { return command; }
        public String getExecCommand() { return execCommand; }
        public String getInstallCommand() { return installCommand; }
        public String getRunCommand() { return runCommand; }
    }

    /**
     * Detects the package manager for an Angular project.
     *
     * @param projectPath Path to the Angular project root
     * @return Detected PackageManager (defaults to NPM)
     */
    public PackageManager detect(Path projectPath) {
        log.info("Detecting package manager for: {}", projectPath);

        // Priority 1: Check for lock files (most reliable)
        if (Files.exists(projectPath.resolve("pnpm-lock.yaml"))) {
            log.info("Detected pnpm (found pnpm-lock.yaml)");
            return PackageManager.PNPM;
        }

        if (Files.exists(projectPath.resolve("yarn.lock"))) {
            log.info("Detected yarn (found yarn.lock)");
            return PackageManager.YARN;
        }

        if (Files.exists(projectPath.resolve("bun.lockb"))) {
            log.info("Detected bun (found bun.lockb)");
            return PackageManager.BUN;
        }

        if (Files.exists(projectPath.resolve("package-lock.json"))) {
            log.info("Detected npm (found package-lock.json)");
            return PackageManager.NPM;
        }

        // Priority 2: Check package.json for packageManager field
        Path packageJson = projectPath.resolve("package.json");
        if (Files.exists(packageJson)) {
            try {
                String content = Files.readString(packageJson);

                if (content.contains("\"packageManager\"")) {
                    if (content.contains("pnpm@")) {
                        log.info("Detected pnpm (packageManager field in package.json)");
                        return PackageManager.PNPM;
                    }
                    if (content.contains("yarn@")) {
                        log.info("Detected yarn (packageManager field in package.json)");
                        return PackageManager.YARN;
                    }
                    if (content.contains("bun@")) {
                        log.info("Detected bun (packageManager field in package.json)");
                        return PackageManager.BUN;
                    }
                    if (content.contains("npm@")) {
                        log.info("Detected npm (packageManager field in package.json)");
                        return PackageManager.NPM;
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read package.json: {}", e.getMessage());
            }
        }

        // Default to npm
        log.info("No package manager detected, defaulting to npm");
        return PackageManager.NPM;
    }

    /**
     * Gets the install command for the detected package manager.
     *
     * @param projectPath Path to the Angular project root
     * @return Install command (e.g., "npm install", "yarn install")
     */
    public String getInstallCommand(Path projectPath) {
        PackageManager pm = detect(projectPath);
        return pm.getInstallCommand();
    }

    /**
     * Gets the Angular serve command for the detected package manager.
     *
     * @param projectPath Path to the Angular project root
     * @param port Port number
     * @return Serve command (e.g., "npx ng serve --port 4200")
     */
    public String getServeCommand(Path projectPath, int port) {
        PackageManager pm = detect(projectPath);

        // Use the package manager's exec command (npx, yarn, pnpm, bunx)
        return String.format("%s ng serve --port %d --host 0.0.0.0",
            pm.getExecCommand(), port);
    }

    /**
     * Validates that the project can be served.
     *
     * @param projectPath Path to the Angular project root
     * @return Validation result with details
     */
    public ValidationResult validate(Path projectPath) {
        StringBuilder issues = new StringBuilder();

        if (!Files.exists(projectPath.resolve("package.json"))) {
            issues.append("- package.json not found\n");
        }

        if (!Files.exists(projectPath.resolve("angular.json"))) {
            issues.append("- angular.json not found (not an Angular project?)\n");
        }

        boolean valid = issues.length() == 0;
        return new ValidationResult(valid, issues.toString());
    }

    public record ValidationResult(boolean valid, String issues) {
        public String getIssues() {
            return issues != null ? issues : "No issues";
        }
    }
}
