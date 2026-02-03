package com.agentivy.backend.tools.github.support;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Manages Git repository operations using JGit.
 *
 * Responsibilities:
 * - Cloning repositories from remote URLs
 * - Managing local repository storage
 * - Cleanup of cloned repositories
 */
@Slf4j
@Component
public class GitRepositoryManager {

    private final Path workDirectory = Path.of(System.getProperty("java.io.tmpdir"), "agentivy");

    /**
     * Clones a Git repository to local filesystem.
     *
     * @param repoUrl GitHub repository URL
     * @param repoName Repository name (used for directory naming)
     * @return Path to the cloned repository
     * @throws Exception if cloning fails
     */
    public Path clone(String repoUrl, String repoName) throws Exception {
        // Create unique directory with timestamp to avoid conflicts
        Path localPath = workDirectory.resolve(repoName + "-" + System.currentTimeMillis());
        Files.createDirectories(localPath);

        log.info("Cloning repository {} to {}", repoUrl, localPath);

        // Clone with depth=1 for faster cloning (shallow clone)
        Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(localPath.toFile())
                .setDepth(1)
                .call()
                .close();

        log.info("Repository cloned successfully to: {}", localPath);
        return localPath;
    }

    /**
     * Cleans up a cloned repository by deleting all files and directories.
     *
     * @param repoPath Path to the repository to clean up
     * @throws IOException if deletion fails
     */
    public void cleanup(Path repoPath) throws IOException {
        if (Files.exists(repoPath)) {
            log.info("Cleaning up repository: {}", repoPath);

            Files.walk(repoPath)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", path, e);
                        }
                    });

            log.info("Repository cleanup complete: {}", repoPath);
        }
    }

    /**
     * Gets the work directory where repositories are cloned.
     *
     * @return Path to the work directory
     */
    public Path getWorkDirectory() {
        return workDirectory;
    }
}
