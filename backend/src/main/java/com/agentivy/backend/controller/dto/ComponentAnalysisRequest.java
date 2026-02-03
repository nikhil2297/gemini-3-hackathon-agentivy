package com.agentivy.backend.controller.dto;

import java.util.List;

/**
 * Request DTO for component analysis endpoint.
 * Allows frontend to send component details directly without re-cloning.
 */
public record ComponentAnalysisRequest(
    String repoPath,
    String repoId,
    List<ComponentInfo> component,
    List<String> tests          // ["accessibility", "performance", "unit", "e2e"]
) {
    /**
     * Information about a single component to analyze.
     */
    public record ComponentInfo(
        String name,
        String tsPath,
        String htmlPath,
        String stylesPath,
        String relativePath
    ) {}
}
