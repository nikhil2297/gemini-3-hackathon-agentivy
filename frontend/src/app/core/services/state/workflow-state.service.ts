import { Injectable, signal, computed } from '@angular/core';
import {
  RepoInfo,
  TestType,
  ActionMode,
  TestResult,
  FixSuggestion,
  WorkflowState,
  ComponentStatus,
  SSESummaryEvent,
} from '../../models/api.models';

/**
 * Centralized state management service using Angular signals
 * Manages the entire workflow state from repository cloning to test results
 */
@Injectable({
  providedIn: 'root',
})
export class WorkflowStateService {
  // ============================================
  // Signal State
  // ============================================

  /**
   * Repository information after successful clone
   */
  readonly repoInfo = signal<RepoInfo | null>(null);

  /**
   * List of selected component class names for testing
   */
  readonly selectedComponents = signal<string[]>([]);

  /**
   * Selected test types (accessibility, performance)
   */
  readonly selectedTests = signal<TestType[]>([]);

  /**
   * Selected action mode (report, suggest, autofix)
   */
  readonly actionMode = signal<ActionMode>('suggest');

  /**
   * Test results for all components
   */
  readonly results = signal<TestResult[]>([]);

  /**
   * AI-generated fix suggestions
   */
  readonly fixSuggestions = signal<FixSuggestion[]>([]);

  /**
   * Summary of analysis results
   */
  readonly summary = signal<SSESummaryEvent['summary'] | undefined>(undefined);

  /**
   * Analysis in progress flag
   */
  readonly isAnalyzing = signal<boolean>(false);

  /**
   * Current analysis phase message
   */
  readonly currentPhase = signal<string>('');

  /**
   * Global error message
   */
  readonly error = signal<string | null>(null);

  // ============================================
  // Computed State
  // ============================================

  /**
   * Check if repository is cloned
   */
  readonly isRepoCloned = computed(() => this.repoInfo() !== null);

  /**
   * Check if configuration is complete
   */
  readonly isConfigured = computed(
    () =>
      this.selectedTests().length > 0 &&
      (this.selectedComponents().length > 0 || this.actionMode() === 'report')
  );

  /**
   * Count of completed tests
   */
  readonly completedTests = computed(
    () =>
      this.results().filter((r) => r.status === ('passed' as ComponentStatus) || r.status === ('failed' as ComponentStatus))
        .length
  );

  /**
   * Count of failed tests
   */
  readonly failedTests = computed(
    () => this.results().filter((r) => r.status === ('failed' as ComponentStatus)).length
  );

  /**
   * Count of passed tests
   */
  readonly passedTests = computed(
    () => this.results().filter((r) => r.status === ('passed' as ComponentStatus)).length
  );

  /**
   * Total violation count from fix suggestions
   */
  readonly totalViolations = computed(() =>
    this.fixSuggestions().reduce(
      (sum, fix) => sum + (fix.violations.violationCount || 0),
      0
    )
  );

  /**
   * Overall workflow state
   */
  readonly workflowState = computed<WorkflowState>(() => ({
    repoInfo: this.repoInfo(),
    selectedComponents: this.selectedComponents(),
    selectedTests: this.selectedTests(),
    actionMode: this.actionMode(),
    results: this.results(),
    summary: this.summary(),
    fixSuggestions: this.fixSuggestions(),
    isAnalyzing: this.isAnalyzing(),
    currentPhase: this.currentPhase(),
    error: this.error(),
  }));

  // ============================================
  // State Mutations
  // ============================================

  /**
   * Set repository information after cloning
   */
  setRepoInfo(info: RepoInfo): void {
    this.repoInfo.set(info);
    this.error.set(null);
  }

  /**
   * Update selected components
   */
  setSelectedComponents(components: string[]): void {
    this.selectedComponents.set(components);
  }

  /**
   * Add a component to selection
   */
  addComponent(component: string): void {
    this.selectedComponents.update((components) => [...components, component]);
  }

  /**
   * Remove a component from selection
   */
  removeComponent(component: string): void {
    this.selectedComponents.update((components) =>
      components.filter((c) => c !== component)
    );
  }

  /**
   * Update selected test types
   */
  setSelectedTests(tests: TestType[]): void {
    this.selectedTests.set(tests);
  }

  /**
   * Toggle a test type
   */
  toggleTest(test: TestType): void {
    this.selectedTests.update((tests) =>
      tests.includes(test) ? tests.filter((t) => t !== test) : [...tests, test]
    );
  }

  /**
   * Set action mode
   */
  setActionMode(mode: ActionMode): void {
    this.actionMode.set(mode);
  }

  /**
   * Start analysis
   */
  startAnalysis(phase: string = 'Starting analysis...'): void {
    this.isAnalyzing.set(true);
    this.currentPhase.set(phase);
    this.error.set(null);
  }

  /**
   * Update analysis phase
   */
  updatePhase(phase: string): void {
    this.currentPhase.set(phase);
  }

  /**
   * Complete analysis
   */
  completeAnalysis(): void {
    this.isAnalyzing.set(false);
    this.currentPhase.set('Analysis completed');
  }

  /**
   * Set error
   */
  setError(error: string): void {
    this.error.set(error);
    this.isAnalyzing.set(false);
  }

  /**
   * Clear error
   */
  clearError(): void {
    this.error.set(null);
  }

  /**
   * Add or update a test result
   */
  updateTestResult(result: TestResult): void {
    this.results.update((results) => {
      const index = results.findIndex((r) => r.component === result.component);
      if (index >= 0) {
        // Update existing result
        const updated = [...results];
        updated[index] = result;
        return updated;
      } else {
        // Add new result
        return [...results, result];
      }
    });
  }

  /**
   * Add a fix suggestion
   */
  addFixSuggestion(suggestion: FixSuggestion): void {
    this.fixSuggestions.update((suggestions) => [...suggestions, suggestion]);
  }

  /**
   * Set analyzing state
   */
  setAnalyzing(analyzing: boolean): void {
    this.isAnalyzing.set(analyzing);
  }

  /**
   * Set all results at once
   */
  setResults(results: TestResult[]): void {
    this.results.set(results);
  }

  /**
   * Set summary results
   */
  setSummary(summary: SSESummaryEvent['summary']): void {
    this.summary.set(summary);
  }

  /**
   * Update component status
   */
  updateComponentStatus(
    componentName: string,
    testType: TestType,
    status: ComponentStatus
  ): void {
    this.results.update((results) => {
      const key = `${componentName}-${testType}`;
      const existing = results.find(
        (r) => r.componentName === componentName && r.testType === testType
      );

      if (existing) {
        return results.map((r) =>
          r.componentName === componentName && r.testType === testType
            ? { ...r, status }
            : r
        );
      } else {
        return [
          ...results,
          {
            componentName,
            testType,
            status,
            timestamp: Date.now(),
          },
        ];
      }
    });
  }

  /**
   * Clear fix suggestions
   */
  clearFixSuggestions(): void {
    this.fixSuggestions.set([]);
  }

  /**
   * Clear all results
   */
  clearResults(): void {
    this.results.set([]);
    this.fixSuggestions.set([]);
  }

  /**
   * Reset entire workflow state
   */
  reset(): void {
    this.repoInfo.set(null);
    this.selectedComponents.set([]);
    this.selectedTests.set([]);
    this.actionMode.set('suggest');
    this.results.set([]);
    this.fixSuggestions.set([]);
    this.isAnalyzing.set(false);
    this.currentPhase.set('');
    this.error.set(null);
  }

  /**
   * Reset only the analysis state (keep configuration)
   */
  resetAnalysis(): void {
    this.results.set([]);
    this.fixSuggestions.set([]);
    this.isAnalyzing.set(false);
    this.currentPhase.set('');
    this.error.set(null);
  }
}
