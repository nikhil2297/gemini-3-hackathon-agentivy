package com.agentivy.backend.tools.github.support;

import org.springframework.stereotype.Component;

/**
 * Parses and validates GitHub repository URLs.
 *
 * Responsibilities:
 * - Extracting repository names from URLs
 * - Extracting owner names from URLs
 * - Validating GitHub URL format
 */
@Component
public class GitHubUrlParser {

    /**
     * Extracts the repository name from a GitHub URL.
     *
     * Examples:
     * - "https://github.com/user/my-repo.git" -> "my-repo"
     * - "https://github.com/user/my-repo" -> "my-repo"
     * - "git@github.com:user/my-repo.git" -> "my-repo"
     *
     * @param url GitHub repository URL
     * @return Repository name
     */
    public String extractRepoName(String url) {
        String cleanUrl = url.replace(".git", "");
        String[] parts = cleanUrl.split("/");
        return parts[parts.length - 1];
    }

    /**
     * Extracts the owner (user or organization) from a GitHub URL.
     *
     * Examples:
     * - "https://github.com/user/my-repo" -> "user"
     * - "git@github.com:user/my-repo.git" -> "user"
     *
     * @param url GitHub repository URL
     * @return Owner name
     * @throws IllegalArgumentException if owner cannot be extracted
     */
    public String extractOwner(String url) {
        String cleanUrl = url.replace(".git", "");

        // Handle SSH URLs: git@github.com:user/repo
        if (cleanUrl.contains("git@github.com:")) {
            String afterColon = cleanUrl.substring(cleanUrl.indexOf(":") + 1);
            String[] parts = afterColon.split("/");
            if (parts.length >= 1) {
                return parts[0];
            }
        }

        // Handle HTTPS URLs: https://github.com/user/repo
        String[] parts = cleanUrl.split("/");
        if (parts.length >= 2) {
            return parts[parts.length - 2];
        }

        throw new IllegalArgumentException("Cannot extract owner from URL: " + url);
    }

    /**
     * Validates if a URL is a valid GitHub repository URL.
     *
     * @param url URL to validate
     * @return true if the URL is a valid GitHub URL
     */
    public boolean isValidGitHubUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        return url.startsWith("https://github.com/") ||
               url.startsWith("http://github.com/") ||
               url.startsWith("git@github.com:");
    }

    /**
     * Normalizes a GitHub URL by ensuring it uses HTTPS format.
     *
     * @param url GitHub URL
     * @return Normalized HTTPS URL
     */
    public String normalizeUrl(String url) {
        if (url.startsWith("git@github.com:")) {
            // Convert SSH to HTTPS: git@github.com:user/repo -> https://github.com/user/repo
            String path = url.substring("git@github.com:".length());
            return "https://github.com/" + path.replace(".git", "");
        }

        // Ensure HTTPS
        if (url.startsWith("http://")) {
            return url.replace("http://", "https://");
        }

        return url.replace(".git", "");
    }
}
