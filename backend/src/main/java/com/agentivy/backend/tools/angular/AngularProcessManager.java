package com.agentivy.backend.tools.angular;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AngularProcessManager {

    private final PackageManagerDetector packageManagerDetector;

    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> processOutputs = new ConcurrentHashMap<>();

    // Safety: prevent OutOfMemoryError for long-running servers
    private static final int MAX_LOG_LENGTH = 100_000;

    public void startDevServer(String repoPath, int port) throws Exception {
        log.info("=== Starting dev server setup ===");
        log.info("Repo path: {}", repoPath);
        log.info("Target port: {}", port);

        stopServer(repoPath); // Ensure clean state

        Path projectPath = Path.of(repoPath);

        // Check if npm is available
        log.info("Checking npm availability...");
        try {
            ProcessBuilder checkNpm = createProcessBuilder("npm --version", projectPath);
            Process npmCheck = checkNpm.start();

            StringBuilder npmOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(npmCheck.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    npmOutput.append(line);
                }
            }

            boolean finished = npmCheck.waitFor(10, TimeUnit.SECONDS);
            int exitCode = finished ? npmCheck.exitValue() : -1;
            log.info("npm --version: finished={}, exitCode={}, output={}", finished, exitCode, npmOutput);

            if (!finished || exitCode != 0) {
                log.error("npm is NOT available! exitCode={}", exitCode);
                throw new RuntimeException("npm check failed with exit code: " + exitCode);
            }
        } catch (Exception e) {
            log.error("npm check FAILED: {}", e.getMessage(), e);
            throw new RuntimeException("npm/npx is not available in this environment: " + e.getMessage(), e);
        }

        // Check if npx is available
        log.info("Checking npx availability...");
        try {
            ProcessBuilder checkNpx = createProcessBuilder("npx --version", projectPath);
            Process npxCheck = checkNpx.start();

            StringBuilder npxOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(npxCheck.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    npxOutput.append(line);
                }
            }

            boolean finished = npxCheck.waitFor(10, TimeUnit.SECONDS);
            int exitCode = finished ? npxCheck.exitValue() : -1;
            log.info("npx --version: finished={}, exitCode={}, output={}", finished, exitCode, npxOutput);

            if (!finished || exitCode != 0) {
                log.warn("npx check returned non-zero exit code: {}", exitCode);
            }
        } catch (Exception e) {
            log.warn("npx check issue: {}", e.getMessage());
        }

        // Auto-detect package manager and use appropriate command
        String command = packageManagerDetector.getServeCommand(projectPath, port);
        log.info("=== Starting ng serve process ===");
        log.info("Command: {}", command);

        ProcessBuilder pb = createProcessBuilder(command, projectPath);
        Process process;
        try {
            process = pb.start();
            log.info("Process started successfully, PID: {}", process.pid());
        } catch (Exception e) {
            log.error("Failed to start ng serve process: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to start ng serve: " + e.getMessage(), e);
        }

        StringBuilder outputCapture = new StringBuilder();
        processOutputs.put(repoPath, outputCapture);
        runningProcesses.put(repoPath, process);

        captureOutput(process, outputCapture);
        log.info("Output capture thread started");
    }

    public void stopServer(String repoPath) {
        Process process = runningProcesses.remove(repoPath);
        processOutputs.remove(repoPath);
        if (process != null && process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
        }
    }

    public String getServerOutput(String repoPath) {
        StringBuilder sb = processOutputs.get(repoPath);
        return sb != null ? sb.toString() : "";
    }

    public boolean isServerProcessAlive(String repoPath) {
        Process p = runningProcesses.get(repoPath);
        return p != null && p.isAlive();
    }

    public void runNpmInstall(Path projectPath) throws Exception {
        // Validate project path exists
        if (!java.nio.file.Files.exists(projectPath)) {
            throw new RuntimeException("Project path does not exist: " + projectPath);
        }
        if (!java.nio.file.Files.exists(projectPath.resolve("package.json"))) {
            throw new RuntimeException("No package.json found in: " + projectPath);
        }

        // Auto-detect package manager and use appropriate install command
        String installCommand = packageManagerDetector.getInstallCommand(projectPath);
        log.info("Installing dependencies with command: {} in directory: {}", installCommand, projectPath);

        ProcessBuilder pb = createProcessBuilder(installCommand, projectPath);
        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            log.error("Failed to start install process: {}", e.getMessage());
            throw new RuntimeException("Failed to start package manager (" + installCommand.split(" ")[0] + "): " + e.getMessage(), e);
        }

        // Capture output for debugging
        StringBuilder installOutput = new StringBuilder();
        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    installOutput.append(line).append("\n");
                    log.debug("Install: {}", line);
                }
            } catch (Exception e) { /* Process ended */ }
        });
        outputThread.setDaemon(true);
        outputThread.start();

        boolean finished = process.waitFor(300, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Installation timed out after 5 minutes. Partial output:\n" + installOutput);
        }
        if (process.exitValue() != 0) {
            String output = installOutput.toString();
            log.error("Installation failed with exit code {}. Output:\n{}", process.exitValue(), output);
            throw new RuntimeException("Installation failed (exit code " + process.exitValue() + "). Output:\n" +
                (output.length() > 2000 ? output.substring(output.length() - 2000) : output));
        }

        log.info("Dependencies installed successfully");
    }

    private ProcessBuilder createProcessBuilder(String cmd, Path dir) {
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        return new ProcessBuilder()
                .command(isWin ? "cmd" : "sh", isWin ? "/c" : "-c", cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true);
    }

    private void captureOutput(Process process, StringBuilder buffer) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (buffer) {
                        // Circular buffer logic: truncate start if too big
                        if (buffer.length() > MAX_LOG_LENGTH) {
                            buffer.delete(0, 10_000);
                        }
                        buffer.append(line).append("\n");
                    }
                }
            } catch (Exception e) { /* Process ended */ }
        });
        t.setDaemon(true);
        t.start();
    }
}