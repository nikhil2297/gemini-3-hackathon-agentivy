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
        stopServer(repoPath); // Ensure clean state

        Path projectPath = Path.of(repoPath);

        // Auto-detect package manager and use appropriate command
        String command = packageManagerDetector.getServeCommand(projectPath, port);
        log.info("Starting dev server with command: {}", command);

        ProcessBuilder pb = createProcessBuilder(command, projectPath);
        Process process = pb.start();

        StringBuilder outputCapture = new StringBuilder();
        processOutputs.put(repoPath, outputCapture);
        runningProcesses.put(repoPath, process);

        captureOutput(process, outputCapture);
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
        // Auto-detect package manager and use appropriate install command
        String installCommand = packageManagerDetector.getInstallCommand(projectPath);
        log.info("Installing dependencies with command: {}", installCommand);

        ProcessBuilder pb = createProcessBuilder(installCommand, projectPath);
        Process process = pb.start();

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
            throw new RuntimeException("Installation timed out after 5 minutes");
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("Installation failed. Output:\n" + installOutput.toString());
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