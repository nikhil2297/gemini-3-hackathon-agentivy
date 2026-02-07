package com.agentivy.backend.util;

/**
 * Centralized scoring logic for the weighted score system and status determination.
 * Used by both accessibility and performance test workflows.
 */
public final class ScoringUtils {

    private ScoringUtils() {
        // Utility class, no instantiation
    }

    /**
     * Calculate weighted score using the formula:
     * Score = 100 - (Critical x 25) - (Serious x 10) - (Moderate x 3) - (Minor x 1)
     * Clamped to [0, 100].
     */
    public static int calculateWeightedScore(int critical, int serious, int moderate, int minor) {
        int score = 100 - (critical * 25) - (serious * 10) - (moderate * 3) - (minor * 1);
        return Math.max(0, Math.min(100, score));
    }

    /**
     * Determine status from a score.
     * Pass: >= 90, Warning: 70-89, Fail: < 70
     */
    public static String statusFromScore(int score) {
        if (score >= 90) return "pass";
        if (score >= 70) return "warning";
        return "fail";
    }

    /**
     * Determine status from a performance score.
     * Performance thresholds differ from accessibility:
     * Pass: >= 70, Warning: 50-69, Fail: < 50
     */
    public static String performanceStatusFromScore(int score) {
        if (score >= 70) return "pass";
        if (score >= 50) return "warning";
        return "fail";
    }

    /**
     * Determine the worst (most severe) status from multiple statuses.
     * fail > warning > pass
     */
    public static String worstStatus(String... statuses) {
        boolean hasFail = false;
        boolean hasWarning = false;
        for (String s : statuses) {
            if ("fail".equals(s)) hasFail = true;
            if ("warning".equals(s)) hasWarning = true;
        }
        if (hasFail) return "fail";
        if (hasWarning) return "warning";
        return "pass";
    }
}
