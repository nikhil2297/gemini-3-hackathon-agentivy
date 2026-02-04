package com.agentivy.backend.tools.testing;

import com.agentivy.backend.service.EventPublisherHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ComponentPerformanceToolTest {

    @Mock
    private EventPublisherHelper eventPublisher;

    private ComponentPerformanceTool tool;

    @BeforeEach
    void setUp() {
        tool = new ComponentPerformanceTool(eventPublisher);
    }

    // --- parseThresholds tests ---

    @Test
    void parseThresholds_nullInput_returnsDefaults() {
        Map<String, Double> result = tool.parseThresholds(null);
        assertEquals(3000.0, result.get("loadTime"));
        assertEquals(1000.0, result.get("renderTime"));
        assertEquals(1800.0, result.get("firstContentfulPaint"));
        assertEquals(2500.0, result.get("largestContentfulPaint"));
        assertEquals(3800.0, result.get("timeToInteractive"));
    }

    @Test
    void parseThresholds_emptyInput_returnsDefaults() {
        Map<String, Double> result = tool.parseThresholds("");
        assertEquals(3000.0, result.get("loadTime"));
    }

    @Test
    void parseThresholds_blankInput_returnsDefaults() {
        Map<String, Double> result = tool.parseThresholds("   ");
        assertEquals(3000.0, result.get("loadTime"));
    }

    @Test
    void parseThresholds_validJson_mergesWithDefaults() {
        String json = "{\"loadTime\": 5000.0, \"renderTime\": 2000.0}";
        Map<String, Double> result = tool.parseThresholds(json);
        assertEquals(5000.0, result.get("loadTime"));
        assertEquals(2000.0, result.get("renderTime"));
        // Non-overridden values stay at defaults
        assertEquals(1800.0, result.get("firstContentfulPaint"));
        assertEquals(2500.0, result.get("largestContentfulPaint"));
    }

    @Test
    void parseThresholds_invalidJson_returnsDefaults() {
        Map<String, Double> result = tool.parseThresholds("not valid json");
        assertEquals(3000.0, result.get("loadTime"));
        assertEquals(1000.0, result.get("renderTime"));
    }

    @Test
    void parseThresholds_unknownKeys_ignored() {
        String json = "{\"loadTime\": 5000.0, \"unknownMetric\": 999.0}";
        Map<String, Double> result = tool.parseThresholds(json);
        assertEquals(5000.0, result.get("loadTime"));
        assertFalse(result.containsKey("unknownMetric"));
    }

    // --- calculateEnhancedPerformanceScore tests ---

    @Test
    void calculateScore_perfectMetrics_returns100() {
        Map<String, Object> allMetrics = new HashMap<>();
        allMetrics.put("initial", Map.of(
            "firstContentfulPaint", 500.0,
            "largestContentfulPaint", 1000.0,
            "loadTime", 1000.0,
            "renderTime", 300.0
        ));
        allMetrics.put("runtime", Map.of(
            "memoryGrowthPercent", 2.0,
            "avgChangeDetectionRate", 1.0
        ));
        allMetrics.put("dom", Map.of("totalElements", 100));

        int score = tool.calculateEnhancedPerformanceScore(allMetrics);
        assertEquals(100, score);
    }

    @Test
    void calculateScore_poorMetrics_lowScore() {
        Map<String, Object> allMetrics = new HashMap<>();
        allMetrics.put("initial", Map.of(
            "firstContentfulPaint", 5000.0,
            "largestContentfulPaint", 6000.0,
            "loadTime", 8000.0,
            "renderTime", 4000.0
        ));
        allMetrics.put("runtime", Map.of(
            "memoryGrowthPercent", 60.0,
            "avgChangeDetectionRate", 25.0
        ));
        allMetrics.put("dom", Map.of("totalElements", 3000));

        int score = tool.calculateEnhancedPerformanceScore(allMetrics);
        assertTrue(score < 30, "Expected score below 30 for poor metrics, got: " + score);
    }

    @Test
    void calculateScore_nullRuntimeMetrics_usesInitialOnly() {
        Map<String, Object> allMetrics = new HashMap<>();
        allMetrics.put("initial", Map.of(
            "firstContentfulPaint", 500.0,
            "largestContentfulPaint", 1000.0,
            "loadTime", 1000.0,
            "renderTime", 300.0
        ));
        allMetrics.put("runtime", null);
        allMetrics.put("dom", null);

        int score = tool.calculateEnhancedPerformanceScore(allMetrics);
        assertEquals(100, score);
    }

    @Test
    void calculateScore_runtimeWithError_skipsRuntimePenalties() {
        Map<String, Object> allMetrics = new HashMap<>();
        allMetrics.put("initial", Map.of(
            "firstContentfulPaint", 500.0,
            "largestContentfulPaint", 1000.0,
            "loadTime", 1000.0,
            "renderTime", 300.0
        ));
        allMetrics.put("runtime", Map.of("error", "Runtime monitoring failed"));
        allMetrics.put("dom", Map.of("totalElements", 100));

        int score = tool.calculateEnhancedPerformanceScore(allMetrics);
        assertEquals(100, score);
    }

    @Test
    void calculateScore_neverBelowZero() {
        Map<String, Object> allMetrics = new HashMap<>();
        allMetrics.put("initial", Map.of(
            "firstContentfulPaint", 100000.0,
            "largestContentfulPaint", 100000.0,
            "loadTime", 100000.0,
            "renderTime", 100000.0
        ));
        allMetrics.put("runtime", Map.of(
            "memoryGrowthPercent", 500.0,
            "avgChangeDetectionRate", 200.0
        ));
        allMetrics.put("dom", Map.of("totalElements", 50000));

        int score = tool.calculateEnhancedPerformanceScore(allMetrics);
        assertEquals(0, score);
    }
}
