package com.agentivy.backend.tools.testing;

import com.agentivy.backend.service.EventPublisherHelper;
import com.agentivy.backend.service.SessionContext;
import com.agentivy.backend.util.ScoringUtils;
import com.agentivy.backend.tools.registry.ToolCategory;
import com.agentivy.backend.tools.registry.ToolMetadata;
import com.agentivy.backend.tools.registry.ToolProvider;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableMap;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import io.reactivex.rxjava3.core.Maybe;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Enhanced atomic tool for component performance testing.
 * Measures initial load, runtime performance, memory usage, and change detection.
 */
@Slf4j
@Component
public class ComponentPerformanceTool implements ToolProvider {

    private final EventPublisherHelper eventPublisher;

    public ComponentPerformanceTool(EventPublisherHelper eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Value("${agentivy.playwright.enabled:true}")
    private boolean playwrightEnabled;

    @Value("${agentivy.playwright.timeout-ms:30000}")
    private int timeoutMs;

    @Value("${agentivy.playwright.headless:true}")
    private boolean headless;

    @Value("${agentivy.performance.runtime-monitoring-seconds:30}")
    private int runtimeMonitoringSeconds;

    @Value("${agentivy.performance.sample-interval-seconds:5}")
    private int sampleIntervalSeconds;

    @Value("${agentivy.performance.dom-element-warning-threshold:1000}")
    private int domElementWarningThreshold;

    private Playwright playwright;
    private Browser browser;

    @PostConstruct
    public void init() {
        if (!playwrightEnabled) {
            log.info("Playwright is disabled for ComponentPerformanceTool via configuration");
            return;
        }

        try {
            log.info("Initializing Playwright browser for performance testing (headless: {})", headless);
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setTimeout(timeoutMs));
            log.info("Playwright browser initialized successfully for performance testing");
        } catch (Exception e) {
            log.error("Failed to initialize Playwright for performance testing: {}", e.getMessage());
            playwrightEnabled = false;
        }
    }

    @PreDestroy
    public void cleanup() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Override
    public ToolMetadata getMetadata() {
        return new ToolMetadata(
            "testing.performance.component",
            "Component Performance Testing Tool",
            "Measures initial load, runtime performance, memory usage, and change detection cycles",
            ToolCategory.COMPONENT_TESTING,
            "2.0.0",
            true,
            List.of("performance", "testing", "runtime", "memory", "change-detection", "playwright"),
            Map.of(
                "metrics", "Initial load, runtime monitoring, memory profiling, change detection",
                "thresholds", "Configurable performance budgets",
                "runtime-monitoring", runtimeMonitoringSeconds + " seconds",
                "sample-interval", sampleIntervalSeconds + " seconds"
            )
        );
    }

    @Override
    public List<FunctionTool> createTools() {
        return List.of(FunctionTool.create(this, "runPerformanceTest"));
    }

    /**
     * Runs comprehensive performance tests on the specified component URL.
     *
     * @param componentUrl The URL of the component to test (e.g., http://localhost:4200/agent-ivy-harness)
     * @param thresholds Optional performance thresholds (JSON string with loadTime, renderTime, etc.)
     * @return Structured performance test results with initial, runtime, and memory metrics
     */
    public Maybe<ImmutableMap<String, Object>> runPerformanceTest(
            @Schema(name = "componentUrl") String componentUrl,
            @Schema(name = "thresholds") String thresholds) {

        return Maybe.fromCallable(() -> {
            log.info("Running enhanced performance test on: {}", componentUrl);

            // Use current component from session context if available, otherwise extract from URL
            String componentName = SessionContext.getCurrentComponent() != null
                ? SessionContext.getCurrentComponent()
                : extractComponentNameFromUrl(componentUrl);
            long startTime = System.currentTimeMillis();

            // Publish starting status with detailed metadata
            eventPublisher.publishToolCall("runPerformanceTest", "Running performance test on " + componentUrl);
            eventPublisher.publishComponentStatus(
                componentName,
                "performance",
                "starting",
                "Initializing performance measurements...",
                Map.of(
                    "targetUrl", componentUrl,
                    "browserReady", playwright != null && browser != null,
                    "metricsToCapture", List.of("initialLoad", "runtime", "memory", "changeDetection"),
                    "monitoringDuration", runtimeMonitoringSeconds * 1000
                )
            );

            try {
                // Check if Playwright is ready (initialized via @PostConstruct)
                if (!playwrightEnabled || browser == null) {
                    return ImmutableMap.<String, Object>builder()
                        .put("status", "error")
                        .put("message", "Playwright not initialized. Ensure agentivy.playwright.enabled=true and Chromium is installed.")
                        .build();
                }

                // Publish in-progress status with phase information
                eventPublisher.publishComponentStatus(
                    componentName,
                    "performance",
                    "in-progress",
                    "Measuring initial load time...",
                    Map.of(
                        "phase", "initial-load",
                        "phasesCompleted", List.of(),
                        "progressPercent", 10
                    )
                );

                // Measure initial load + runtime metrics
                Map<String, Object> allMetrics = measureComprehensivePerformance(componentUrl);

                // Update progress during runtime monitoring
                eventPublisher.publishComponentStatus(
                    componentName,
                    "performance",
                    "in-progress",
                    String.format("Monitoring runtime performance (%ds)...", runtimeMonitoringSeconds),
                    Map.of(
                        "phase", "runtime-monitoring",
                        "phasesCompleted", List.of("initial-load"),
                        "progressPercent", 60
                    )
                );

                if (allMetrics.containsKey("error")) {
                    return ImmutableMap.<String, Object>builder()
                        .put("status", "error")
                        .put("message", allMetrics.get("error"))
                        .build();
                }

                // Parse thresholds (if provided)
                Map<String, Double> performanceThresholds = parseThresholds(thresholds);

                // Evaluate metrics against thresholds
                Map<String, Object> initialMetrics = (Map<String, Object>) allMetrics.get("initial");
                Map<String, Object> evaluation = evaluatePerformance(initialMetrics, performanceThresholds);

                // Calculate performance score (0-100) - now includes runtime penalties
                int performanceScore = calculateEnhancedPerformanceScore(allMetrics);

                // Generate warnings based on runtime data
                List<String> warnings = generateWarnings(allMetrics);
                List<String> recommendations = generateEnhancedRecommendations(allMetrics, evaluation, warnings);

                long timeElapsed = System.currentTimeMillis() - startTime;

                log.info("✓ Enhanced performance test completed: score={}, warnings={}",
                    performanceScore, warnings.size());

                // Extract detailed metrics for metadata
                Map<String, Object> initialMetricsData = (Map<String, Object>) allMetrics.get("initial");
                Map<String, Object> runtimeMetricsData = (Map<String, Object>) allMetrics.get("runtime");

                // Build threshold results
                Map<String, Object> thresholdResults = buildThresholdResults(allMetrics, performanceThresholds);

                // Publish completed status with comprehensive metadata
                eventPublisher.publishComponentStatus(
                    componentName,
                    "performance",
                    "completed",
                    warnings.isEmpty()
                        ? String.format("Performance score: %d/100", performanceScore)
                        : String.format("Performance score: %d/100 (%d warnings)", performanceScore, warnings.size()),
                    Map.of(
                        "passed", "pass".equals(ScoringUtils.performanceStatusFromScore(performanceScore)),
                        "performanceScore", performanceScore,
                        "warningCount", warnings.size(),
                        "initialLoad", initialMetricsData != null ? initialMetricsData : Map.of(),
                        "runtime", runtimeMetricsData != null ? runtimeMetricsData : Map.of(),
                        "thresholds", thresholdResults,
                        "warnings", warnings,
                        "recommendations", recommendations.subList(0, Math.min(3, recommendations.size())), // Top 3
                        "timeElapsed", timeElapsed
                    )
                );

                return ImmutableMap.<String, Object>builder()
                    .put("status", "success")
                    .put("componentUrl", componentUrl)
                    .put("timestamp", System.currentTimeMillis())
                    .put("metrics", allMetrics)
                    .put("performanceScore", performanceScore)
                    .put("evaluation", evaluation)
                    .put("passed", "pass".equals(ScoringUtils.performanceStatusFromScore(performanceScore)))
                    .put("warnings", warnings)
                    .put("recommendations", generateEnhancedRecommendations(allMetrics, evaluation, warnings))
                    .build();

            } catch (Exception e) {
                log.error("Performance test failed", e);

                // Publish failed status
                eventPublisher.publishComponentStatus(
                    componentName,
                    "performance",
                    "failed",
                    "Performance test failed: " + e.getMessage()
                );

                return ImmutableMap.<String, Object>builder()
                    .put("status", "error")
                    .put("message", "Performance test failed: " + e.getMessage())
                    .build();
            }
        });
    }

    /**
     * Extract component name from URL.
     */
    private String extractComponentNameFromUrl(String url) {
        try {
            String path = url.substring(url.lastIndexOf("/") + 1);
            return path.isEmpty() ? "unknown" : path;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Build threshold comparison results.
     */
    private Map<String, Object> buildThresholdResults(Map<String, Object> allMetrics, Map<String, Double> thresholds) {
        Map<String, Object> results = new java.util.HashMap<>();

        Map<String, Object> initial = (Map<String, Object>) allMetrics.get("initial");
        Map<String, Object> runtime = (Map<String, Object>) allMetrics.get("runtime");

        if (initial != null) {
            Number loadTime = (Number) initial.get("loadTime");
            if (loadTime != null && thresholds.containsKey("loadTime")) {
                results.put("loadTime", Map.of(
                    "threshold", thresholds.get("loadTime"),
                    "actual", loadTime.doubleValue(),
                    "passed", loadTime.doubleValue() <= thresholds.get("loadTime")
                ));
            }
        }

        if (runtime != null) {
            Number memoryUsage = (Number) runtime.get("memoryUsageMB");
            if (memoryUsage != null && thresholds.containsKey("memoryUsage")) {
                results.put("memoryUsage", Map.of(
                    "threshold", thresholds.get("memoryUsage"),
                    "actual", memoryUsage.doubleValue(),
                    "passed", memoryUsage.doubleValue() <= thresholds.get("memoryUsage")
                ));
            }

            Number domElements = (Number) runtime.get("domElementCount");
            if (domElements != null && thresholds.containsKey("domElements")) {
                results.put("domElements", Map.of(
                    "threshold", thresholds.get("domElements"),
                    "actual", domElements.intValue(),
                    "passed", domElements.intValue() <= thresholds.get("domElements")
                ));
            }
        }

        return results;
    }

    /**
     * Comprehensive performance measurement: initial load + runtime monitoring.
     */
    private Map<String, Object> measureComprehensivePerformance(String url) {
        try (BrowserContext context = browser.newContext();
             Page page = context.newPage()) {

            Map<String, Object> result = new HashMap<>();

            // Step 1: Initial Load Metrics
            log.info("Step 1: Measuring initial load performance...");
            Map<String, Object> initialMetrics = measureInitialLoadMetrics(page, url);

            if (initialMetrics.containsKey("error")) {
                return initialMetrics;
            }

            result.put("initial", initialMetrics);

            // Step 2: DOM Element Count Check
            log.info("Step 2: Counting DOM elements...");
            Map<String, Object> domMetrics = measureDOMComplexity(page);
            result.put("dom", domMetrics);

            // Step 3: Runtime Monitoring (memory + change detection over time)
            log.info("Step 3: Starting runtime monitoring for {} seconds...", runtimeMonitoringSeconds);
            Map<String, Object> runtimeMetrics = measureRuntimePerformance(page);
            result.put("runtime", runtimeMetrics);

            return result;

        } catch (Exception e) {
            log.error("Failed to measure comprehensive performance", e);
            return Map.of("error", "Comprehensive performance measurement failed: " + e.getMessage());
        }
    }

    /**
     * Step 1: Measure initial load performance.
     */
    private Map<String, Object> measureInitialLoadMetrics(Page page, String url) {
        try {
            long startTime = System.currentTimeMillis();
            page.navigate(url, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(timeoutMs));
            long loadTime = System.currentTimeMillis() - startTime;

            // Get Performance API metrics with hard timeout
            @SuppressWarnings("unchecked")
            Map<String, Object> performanceMetrics;
            try {
                log.info("Collecting initial performance metrics with 20s timeout...");

                var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                var future = executor.submit(() -> {
                    try {
                        return (Map<String, Object>) page.evaluate("""
                            () => {
                                const perfData = window.performance.getEntriesByType('navigation')[0];
                                const paintEntries = window.performance.getEntriesByType('paint');

                                const fcp = paintEntries.find(e => e.name === 'first-contentful-paint');
                                const lcp = window.performance.getEntriesByType('largest-contentful-paint').slice(-1)[0];

                                return {
                                    loadTime: perfData ? perfData.loadEventEnd - perfData.fetchStart : 0,
                                    domContentLoaded: perfData ? perfData.domContentLoadedEventEnd - perfData.fetchStart : 0,
                                    renderTime: perfData ? perfData.domComplete - perfData.domInteractive : 0,
                                    firstContentfulPaint: fcp ? fcp.startTime : 0,
                                    largestContentfulPaint: lcp ? lcp.startTime : 0,
                                    timeToInteractive: perfData ? perfData.domInteractive - perfData.fetchStart : 0
                                };
                            }
                        """);
                    } catch (Exception e) {
                        throw new RuntimeException("Performance evaluate failed: " + e.getMessage(), e);
                    }
                });

                try {
                    performanceMetrics = future.get(20, java.util.concurrent.TimeUnit.SECONDS);
                    log.info("Initial performance metrics collected successfully");
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    log.error("Performance metrics evaluation timed out after 20s");
                    return Map.of("error", "Performance metrics evaluation timed out after 20s - page too complex");
                } finally {
                    executor.shutdownNow();
                }
            } catch (Exception e) {
                log.error("Performance metrics evaluation failed", e);
                return Map.of("error", "Performance metrics evaluation failed: " + e.getMessage());
            }

            performanceMetrics.put("measuredLoadTime", loadTime);
            return performanceMetrics;

        } catch (Exception e) {
            log.error("Failed to measure initial load", e);
            return Map.of("error", "Initial load measurement failed: " + e.getMessage());
        }
    }

    /**
     * Step 2: Measure DOM complexity.
     */
    private Map<String, Object> measureDOMComplexity(Page page) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> domStats = (Map<String, Object>) page.evaluate("""
                () => {
                    const allElements = document.querySelectorAll('*');
                    const depth = (el) => {
                        let d = 0;
                        while (el.parentElement) { d++; el = el.parentElement; }
                        return d;
                    };

                    return {
                        totalElements: allElements.length,
                        maxDepth: Math.max(...Array.from(allElements).map(depth)),
                        bodyElements: document.body ? document.body.querySelectorAll('*').length : 0
                    };
                }
            """);

            int totalElements = ((Number) domStats.get("totalElements")).intValue();
            boolean complexPage = totalElements > domElementWarningThreshold;

            domStats.put("isComplex", complexPage);
            domStats.put("threshold", domElementWarningThreshold);

            log.info("DOM complexity: {} elements (threshold: {})", totalElements, domElementWarningThreshold);

            return domStats;

        } catch (Exception e) {
            log.warn("Failed to measure DOM complexity: {}", e.getMessage());
            return Map.of(
                "totalElements", 0,
                "maxDepth", 0,
                "error", e.getMessage()
            );
        }
    }

    /**
     * Step 3: Runtime monitoring - memory and change detection over time.
     *
     * Note: This method uses Thread.sleep() for sampling intervals, which blocks
     * the current thread. This is intentional because:
     * 1. It runs inside Maybe.fromCallable() on a bounded scheduler
     * 2. The monitoring loop is inherently sequential (sample, wait, sample)
     * 3. Each invocation creates its own browser context, so blocking is isolated
     */
    private Map<String, Object> measureRuntimePerformance(Page page) {
        try {
            // Inject Angular change detection monitor
            injectChangeDetectionMonitor(page);

            List<Map<String, Object>> samples = new ArrayList<>();
            int samplesCount = runtimeMonitoringSeconds / sampleIntervalSeconds;

            for (int i = 0; i < samplesCount; i++) {
                Thread.sleep(sampleIntervalSeconds * 1000L);

                @SuppressWarnings("unchecked")
                Map<String, Object> sample = (Map<String, Object>) page.evaluate("""
                    () => {
                        const memory = performance.memory || {};
                        const cdStats = window.__cdMonitor || { cycles: 0 };

                        return {
                            timestamp: Date.now(),
                            usedJSHeapSize: memory.usedJSHeapSize || 0,
                            totalJSHeapSize: memory.totalJSHeapSize || 0,
                            changeDetectionCycles: cdStats.cycles,
                            changeDetectionRate: cdStats.rate || 0
                        };
                    }
                """);

                samples.add(sample);

                log.info("  Sample {}/{}: memory={}MB, CD cycles={}",
                    i + 1, samplesCount,
                    String.format("%.2f", ((Number) sample.get("usedJSHeapSize")).doubleValue() / 1024 / 1024),
                    sample.get("changeDetectionCycles"));
            }

            // Analyze samples
            return analyzeSamples(samples);

        } catch (Exception e) {
            log.warn("Runtime monitoring failed: {}", e.getMessage());
            return Map.of(
                "error", "Runtime monitoring failed: " + e.getMessage(),
                "samples", List.of()
            );
        }
    }

    /**
     * Inject Angular change detection monitor into the page.
     */
    private void injectChangeDetectionMonitor(Page page) {
        try {
            page.evaluate("""
                () => {
                    if (window.__cdMonitor) return; // Already injected

                    window.__cdMonitor = { cycles: 0, rate: 0, lastCheck: Date.now(), cyclesSinceLastCheck: 0 };

                    // Hook into Angular's zone if available.
                    // NOTE: This only hooks Zone.current.run. Angular also uses
                    // runTask, runGuarded, and scheduleTask on the Zone prototype.
                    // If Zone.current.run is not triggered, the rate will read as 0.
                    // A more robust approach would use ng.profiler or
                    // getAllAngularRootElements() if available, but those APIs are
                    // only present in development mode.
                    if (window.Zone && window.Zone.current) {
                        const originalRun = window.Zone.current.run;
                        window.Zone.current.run = function(...args) {
                            window.__cdMonitor.cycles++;
                            window.__cdMonitor.cyclesSinceLastCheck++;

                            // Calculate rate (cycles per second) over last interval
                            const now = Date.now();
                            const elapsed = (now - window.__cdMonitor.lastCheck) / 1000;
                            if (elapsed >= 1) {
                                window.__cdMonitor.rate = window.__cdMonitor.cyclesSinceLastCheck / elapsed;
                                window.__cdMonitor.lastCheck = now;
                                window.__cdMonitor.cyclesSinceLastCheck = 0;
                            }

                            return originalRun.apply(this, args);
                        };
                    }
                }
            """);
            log.info("Change detection monitor injected successfully");
        } catch (Exception e) {
            log.warn("Failed to inject change detection monitor: {}", e.getMessage());
        }
    }

    /**
     * Analyze runtime samples to detect trends.
     */
    private Map<String, Object> analyzeSamples(List<Map<String, Object>> samples) {
        if (samples.isEmpty()) {
            return Map.of("samples", samples, "analysis", "No samples collected");
        }

        // Memory analysis
        double firstMemory = ((Number) samples.get(0).get("usedJSHeapSize")).doubleValue();
        double lastMemory = ((Number) samples.get(samples.size() - 1).get("usedJSHeapSize")).doubleValue();
        double memoryGrowth = lastMemory - firstMemory;
        double memoryGrowthPercent = (memoryGrowth / firstMemory) * 100;

        // Change detection analysis
        int firstCD = ((Number) samples.get(0).get("changeDetectionCycles")).intValue();
        int lastCD = ((Number) samples.get(samples.size() - 1).get("changeDetectionCycles")).intValue();
        int totalCDCycles = lastCD - firstCD;
        double avgCDRate = samples.stream()
            .mapToDouble(s -> ((Number) s.get("changeDetectionRate")).doubleValue())
            .average()
            .orElse(0.0);

        Map<String, Object> analysis = new HashMap<>();
        analysis.put("samples", samples);
        analysis.put("sampleCount", samples.size());
        analysis.put("memoryGrowthBytes", memoryGrowth);
        analysis.put("memoryGrowthPercent", memoryGrowthPercent);
        analysis.put("memoryGrowthMB", memoryGrowth / 1024 / 1024);
        analysis.put("initialMemoryMB", firstMemory / 1024 / 1024);
        analysis.put("finalMemoryMB", lastMemory / 1024 / 1024);
        analysis.put("changeDetectionCycles", totalCDCycles);
        analysis.put("avgChangeDetectionRate", avgCDRate);

        // Memory leak detection
        boolean potentialMemoryLeak = memoryGrowthPercent > 20; // >20% growth is suspicious
        analysis.put("potentialMemoryLeak", potentialMemoryLeak);

        // Excessive change detection
        boolean excessiveCD = avgCDRate > 10; // >10 CD cycles per second is excessive
        analysis.put("excessiveChangeDetection", excessiveCD);

        return analysis;
    }

    /**
     * Calculate enhanced performance score including runtime penalties.
     */
    int calculateEnhancedPerformanceScore(Map<String, Object> allMetrics) {
        Map<String, Object> initialMetrics = (Map<String, Object>) allMetrics.get("initial");
        Map<String, Object> runtimeMetrics = (Map<String, Object>) allMetrics.get("runtime");
        Map<String, Object> domMetrics = (Map<String, Object>) allMetrics.get("dom");

        // Start with initial score (0-100)
        double score = 100.0;

        // Initial load penalties (60% weight)
        if (initialMetrics.containsKey("firstContentfulPaint")) {
            double fcp = ((Number) initialMetrics.get("firstContentfulPaint")).doubleValue();
            score -= calculatePenalty(fcp, 1800, 3000) * 0.10;
        }

        if (initialMetrics.containsKey("largestContentfulPaint")) {
            double lcp = ((Number) initialMetrics.get("largestContentfulPaint")).doubleValue();
            score -= calculatePenalty(lcp, 2500, 4000) * 0.20;
        }

        if (initialMetrics.containsKey("loadTime")) {
            double loadTime = ((Number) initialMetrics.get("loadTime")).doubleValue();
            score -= calculatePenalty(loadTime, 3000, 5000) * 0.20;
        }

        if (initialMetrics.containsKey("renderTime")) {
            double renderTime = ((Number) initialMetrics.get("renderTime")).doubleValue();
            score -= calculatePenalty(renderTime, 1000, 2000) * 0.10;
        }

        // Runtime penalties (30% weight)
        if (runtimeMetrics != null && !runtimeMetrics.containsKey("error")) {
            // Memory growth penalty
            if (runtimeMetrics.containsKey("memoryGrowthPercent")) {
                double memGrowth = ((Number) runtimeMetrics.get("memoryGrowthPercent")).doubleValue();
                score -= calculatePenalty(memGrowth, 10, 50) * 0.15; // 10-50% growth range
            }

            // Change detection penalty
            if (runtimeMetrics.containsKey("avgChangeDetectionRate")) {
                double cdRate = ((Number) runtimeMetrics.get("avgChangeDetectionRate")).doubleValue();
                score -= calculatePenalty(cdRate, 5, 20) * 0.15; // 5-20 CD/sec range
            }
        }

        // DOM complexity penalty (10% weight)
        if (domMetrics != null && domMetrics.containsKey("totalElements")) {
            int elements = ((Number) domMetrics.get("totalElements")).intValue();
            score -= calculatePenalty(elements, 500, 2000) * 0.10; // 500-2000 elements range
        }

        return Math.max(0, Math.min(100, (int) Math.round(score)));
    }

    /**
     * Generate warnings based on runtime data.
     */
    private List<String> generateWarnings(Map<String, Object> allMetrics) {
        List<String> warnings = new ArrayList<>();

        Map<String, Object> domMetrics = (Map<String, Object>) allMetrics.get("dom");
        Map<String, Object> runtimeMetrics = (Map<String, Object>) allMetrics.get("runtime");

        // DOM warnings
        if (domMetrics != null && domMetrics.containsKey("isComplex")) {
            boolean isComplex = (boolean) domMetrics.get("isComplex");
            int totalElements = ((Number) domMetrics.get("totalElements")).intValue();
            if (isComplex) {
                warnings.add(String.format("DOM is complex with %d elements (threshold: %d) - consider virtual scrolling or pagination",
                    totalElements, domElementWarningThreshold));
            }
        }

        // Runtime warnings
        if (runtimeMetrics != null && !runtimeMetrics.containsKey("error")) {
            boolean memoryLeak = (boolean) runtimeMetrics.getOrDefault("potentialMemoryLeak", false);
            boolean excessiveCD = (boolean) runtimeMetrics.getOrDefault("excessiveChangeDetection", false);

            if (memoryLeak) {
                double growthMB = ((Number) runtimeMetrics.get("memoryGrowthMB")).doubleValue();
                warnings.add(String.format("Potential memory leak detected: %.2f MB growth in %d seconds - check for uncleared intervals/subscriptions",
                    growthMB, runtimeMonitoringSeconds));
            }

            if (excessiveCD) {
                double cdRate = ((Number) runtimeMetrics.get("avgChangeDetectionRate")).doubleValue();
                warnings.add(String.format("Excessive change detection: %.1f cycles/sec - use OnPush strategy or detach change detector",
                    cdRate));
            }
        }

        return warnings;
    }

    /**
     * Generate enhanced recommendations including runtime issues.
     */
    private List<String> generateEnhancedRecommendations(
            Map<String, Object> allMetrics,
            Map<String, Object> evaluation,
            List<String> warnings) {

        List<String> recommendations = new ArrayList<>();

        // Initial load recommendations
        List<String> failedMetrics = (List<String>) evaluation.get("failedMetrics");
        if (failedMetrics != null && !failedMetrics.isEmpty()) {
            for (String metric : failedMetrics) {
                switch (metric) {
                    case "loadTime":
                        recommendations.add("Reduce initial bundle size - consider lazy loading and code splitting");
                        break;
                    case "renderTime":
                        recommendations.add("Optimize component render cycle - check for unnecessary re-renders");
                        break;
                    case "firstContentfulPaint":
                        recommendations.add("Optimize critical rendering path - inline critical CSS");
                        break;
                    case "largestContentfulPaint":
                        recommendations.add("Optimize image loading - use proper sizing and lazy loading");
                        break;
                }
            }
        }

        // Runtime recommendations based on warnings
        Map<String, Object> runtimeMetrics = (Map<String, Object>) allMetrics.get("runtime");
        if (runtimeMetrics != null && !runtimeMetrics.containsKey("error")) {
            if ((boolean) runtimeMetrics.getOrDefault("potentialMemoryLeak", false)) {
                recommendations.add("Clear intervals and unsubscribe from observables in ngOnDestroy");
                recommendations.add("Use takeUntil pattern for RxJS subscriptions");
            }

            if ((boolean) runtimeMetrics.getOrDefault("excessiveChangeDetection", false)) {
                recommendations.add("Use ChangeDetectionStrategy.OnPush for better performance");
                recommendations.add("Consider using ChangeDetectorRef.detach() for complex components");
                recommendations.add("Avoid functions in templates - use pure pipes or computed values");
            }
        }

        // DOM recommendations
        Map<String, Object> domMetrics = (Map<String, Object>) allMetrics.get("dom");
        if (domMetrics != null && (boolean) domMetrics.getOrDefault("isComplex", false)) {
            recommendations.add("Implement virtual scrolling with CDK or ngx-virtual-scroller");
            recommendations.add("Consider pagination or lazy loading for large lists");
        }

        if (recommendations.isEmpty() && warnings.isEmpty()) {
            recommendations.add("Component performance is excellent - no optimizations needed");
        }

        return recommendations;
    }

    /**
     * Parses threshold JSON string into a map, merging with defaults.
     * Unknown keys in the input are ignored.
     */
    Map<String, Double> parseThresholds(String thresholds) {
        Map<String, Double> defaults = new HashMap<>();
        defaults.put("loadTime", 3000.0);
        defaults.put("renderTime", 1000.0);
        defaults.put("firstContentfulPaint", 1800.0);
        defaults.put("largestContentfulPaint", 2500.0);
        defaults.put("timeToInteractive", 3800.0);
        defaults.put("memoryUsage", 50.0);
        defaults.put("domElements", 1500.0);

        if (thresholds == null || thresholds.isBlank()) {
            return defaults;
        }

        try {
            com.google.gson.JsonObject json = com.google.gson.JsonParser
                .parseString(thresholds).getAsJsonObject();
            for (String key : new ArrayList<>(defaults.keySet())) {
                if (json.has(key) && json.get(key).isJsonPrimitive()) {
                    defaults.put(key, json.get(key).getAsDouble());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse thresholds JSON, using defaults: {}", e.getMessage());
        }

        return defaults;
    }

    /**
     * Evaluates metrics against thresholds.
     */
    private Map<String, Object> evaluatePerformance(
            Map<String, Object> metrics,
            Map<String, Double> thresholds) {

        Map<String, Object> evaluation = new HashMap<>();
        List<String> failedMetrics = new ArrayList<>();

        for (Map.Entry<String, Double> threshold : thresholds.entrySet()) {
            String metricName = threshold.getKey();
            Double thresholdValue = threshold.getValue();

            if (metrics.containsKey(metricName)) {
                Object metricValue = metrics.get(metricName);
                double value = metricValue instanceof Number ?
                    ((Number) metricValue).doubleValue() : 0.0;

                boolean passed = value <= thresholdValue;
                evaluation.put(metricName, Map.of(
                    "value", value,
                    "threshold", thresholdValue,
                    "passed", passed,
                    "unit", "ms"
                ));

                if (!passed) {
                    failedMetrics.add(metricName);
                }
            }
        }

        evaluation.put("allPassed", failedMetrics.isEmpty());
        evaluation.put("failedMetrics", failedMetrics);

        return evaluation;
    }

    /**
     * Calculates penalty percentage based on metric value.
     */
    private double calculatePenalty(double value, double good, double poor) {
        if (value <= good) return 0.0;
        if (value >= poor) return 100.0;
        return ((value - good) / (poor - good)) * 100.0;
    }
}
