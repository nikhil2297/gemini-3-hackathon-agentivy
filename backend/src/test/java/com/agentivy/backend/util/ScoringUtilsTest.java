package com.agentivy.backend.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ScoringUtilsTest {

    @Test
    void calculateWeightedScore_noViolations_returns100() {
        assertEquals(100, ScoringUtils.calculateWeightedScore(0, 0, 0, 0));
    }

    @Test
    void calculateWeightedScore_oneCritical_returns75() {
        assertEquals(75, ScoringUtils.calculateWeightedScore(1, 0, 0, 0));
    }

    @Test
    void calculateWeightedScore_clampsToZero() {
        assertEquals(0, ScoringUtils.calculateWeightedScore(5, 0, 0, 0));
    }

    @Test
    void calculateWeightedScore_mixedSeverities() {
        // 100 - 25 - 10 - 3 - 1 = 61
        assertEquals(61, ScoringUtils.calculateWeightedScore(1, 1, 1, 1));
    }

    @ParameterizedTest
    @CsvSource({
        "100, pass",
        "95, pass",
        "90, pass",
        "89, warning",
        "70, warning",
        "69, fail",
        "0, fail"
    })
    void statusFromScore(int score, String expectedStatus) {
        assertEquals(expectedStatus, ScoringUtils.statusFromScore(score));
    }

    @ParameterizedTest
    @CsvSource({
        "100, pass",
        "70, pass",
        "69, warning",
        "50, warning",
        "49, fail",
        "0, fail"
    })
    void performanceStatusFromScore(int score, String expectedStatus) {
        assertEquals(expectedStatus, ScoringUtils.performanceStatusFromScore(score));
    }

    @Test
    void worstStatus_allPass() {
        assertEquals("pass", ScoringUtils.worstStatus("pass", "pass"));
    }

    @Test
    void worstStatus_withWarning() {
        assertEquals("warning", ScoringUtils.worstStatus("pass", "warning", "pass"));
    }

    @Test
    void worstStatus_withFail() {
        assertEquals("fail", ScoringUtils.worstStatus("pass", "warning", "fail"));
    }
}
