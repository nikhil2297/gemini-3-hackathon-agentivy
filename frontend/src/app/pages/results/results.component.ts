import { Component, computed, signal, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { CardComponent } from '../../shared/components/card/card.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { CodeBlockComponent } from '../../shared/components/code-block/code-block.component';
import { SpinnerComponent } from '../../shared/components/spinner/spinner.component';
import { ProgressBarComponent } from '../../shared/components/progress-bar/progress-bar.component';
import { WorkflowStateService } from '../../core/services/state/workflow-state.service';
import { AnalysisService } from '../../core/services/api/analysis.service';
import { SseService } from '../../core/services/sse/sse.service';
import { TestResult, FixSuggestion, ComponentStatus, SuggestFixConfig, TestType, SSESummaryEvent, SSEComponentResultEvent, ActionableDetail, ResultIssue } from '../../core/models/api.models';

interface ToolStatus {
  tool: string;
  status: 'starting' | 'in-progress' | 'completed' | 'failed' | 'stopped';
  message: string;
  metadata?: any;
  timestamp: number;
  result?: TestResult;
  suggestion?: FixSuggestion;
}

@Component({
  selector: 'app-results',
  standalone: true,
  imports: [
    CommonModule,
    ButtonComponent,
    StatusBadgeComponent,
    CodeBlockComponent,
    SpinnerComponent,
    ProgressBarComponent
  ],
  templateUrl: './results.component.html',
  styleUrl: './results.component.css',
})
export class ResultsComponent implements OnInit, OnDestroy {
  private readonly workflowState = signal(new WorkflowStateService());
  private readonly analysisService = signal(new AnalysisService());
  private readonly sseService = signal(new SseService());
  private readonly router = signal(new Router());

  private sseSubscription?: Subscription;
  private expandedRows = signal<Set<string>>(new Set());

  // Track tool status for each component in real-time
  private componentToolStatus = signal<Map<string, ToolStatus[]>>(new Map());

  repoInfo = computed(() => this.workflowState().repoInfo());
  selectedComponents = computed(() => this.workflowState().selectedComponents());
  selectedTests = computed(() => this.workflowState().selectedTests());
  actionMode = computed(() => this.workflowState().actionMode());
  isAnalyzing = computed(() => this.workflowState().isAnalyzing());
  allResults = computed(() => this.workflowState().results());
  summary = computed(() => this.workflowState().summary());

  completedResults = computed(() =>
    this.allResults().filter(r => r.status === ('passed' as ComponentStatus) || r.status === ('failed' as ComponentStatus))
  );

  // Group results by component name and include tool statuses
  groupedResults = computed(() => {

    console.log('allResults', this.allResults());
    console.log('componentToolStatus', this.componentToolStatus());

    const toolStatuses = this.componentToolStatus();
    const results = this.allResults();

    // Get all unique component names from both tool statuses and results
    const componentNames = new Set<string>();
    toolStatuses.forEach((_, componentName) => componentNames.add(componentName));
    results.forEach(result => componentNames.add(result.componentName));

    // Build grouped data
    return Array.from(componentNames).map(componentName => {
      const componentResults = results.filter(r => r.componentName === componentName && r.testType != undefined);
      const tools = toolStatuses.get(componentName) || [];

      // Keep all tools to show full progress timeline
      const sortedTools = tools.sort((a, b) => a.timestamp - b.timestamp);

      // Calculate overall status
      const overallStatus = this.calculateOverallStatus(componentResults, sortedTools);

      // Calculate total issues
      const totalIssues = this.calculateTotalIssues(componentResults);

      console.log('overallStatus', overallStatus);
      console.log('totalIssues', totalIssues);
      console.log('componentResults', componentResults);
      console.log('tools', tools);

      return {
        componentName,
        results: componentResults,
        tools: sortedTools,
        overallStatus,
        totalIssues,
      };
    });
  });

  progress = signal(0);
  progressLabel = signal('Initializing...');
  currentStatus = signal('');

  constructor(
    workflowState: WorkflowStateService,
    analysisService: AnalysisService,
    sseService: SseService,
    router: Router
  ) {
    this.workflowState.set(workflowState);
    this.analysisService.set(analysisService);
    this.sseService.set(sseService);
    this.router.set(router);
  }

  ngOnInit(): void {
    if (!this.repoInfo() || this.selectedComponents().length === 0) {
      this.router().navigate(['/']);
      return;
    }

    this.startAnalysis();
  }

  ngOnDestroy(): void {
    this.sseSubscription?.unsubscribe();
  }

  private startAnalysis(): void {
    const repoInfo = this.repoInfo();
    if (!repoInfo) return;

    const mode = this.actionMode();

    if (mode === 'report') {
      this.startReportGeneration();
    } else if (mode === 'suggest' || mode === 'autofix') {
      this.startSuggestFixes();
    }
  }

  private startReportGeneration(): void {
    const repoInfo = this.repoInfo();
    if (!repoInfo) return;

    // Map selected component names to full AnalysisComponent objects
    const selectedComponentNames = this.selectedComponents();
    const selectedComponentObjects = (repoInfo.discoveredComponents || [])
      .filter(c => selectedComponentNames.includes(c.name));

    this.analysisService().generateReport({
      repoPath: repoInfo.localPath,
      repoId: repoInfo.repoId,
      component: selectedComponentObjects,
      tests: this.selectedTests(),
    }).subscribe({
      next: (response) => {
        this.workflowState().setResults(response.results);
        this.workflowState().setAnalyzing(false);
        this.progress.set(100);
        this.progressLabel.set('Complete');
      },
      error: (error) => {
        console.error('Analysis error:', error);
        this.workflowState().setAnalyzing(false);
        this.currentStatus.set('Error: ' + (error.error?.message || 'Analysis failed'));
      },
    });
  }

  private startSuggestFixes(): void {
    const repoInfo = this.repoInfo();
    if (!repoInfo) return;

    // Map selected component names to full AnalysisComponent objects
    const selectedComponentNames = this.selectedComponents();
    const selectedComponentObjects = (repoInfo.discoveredComponents || [])
      .filter(c => selectedComponentNames.includes(c.name));

    const config: SuggestFixConfig = {
      repoPath: repoInfo.localPath,
      repoId: repoInfo.repoId,
      component: selectedComponentObjects,
      tests: this.selectedTests(),
    };

    this.sseSubscription = this.sseService().connectToSuggestFixes(config).subscribe({
      next: (event) => {
        switch (event.type) {
          case 'started':
            this.progressLabel.set('Analysis started');
            this.currentStatus.set('Connecting to server...');
            break;

          case 'progress':
            if (event.data) {
              this.progress.set(event.data.progress || 0);
              this.currentStatus.set(event.data.message || '');
              if (event.data.message) {
                this.progressLabel.set(event.data.message);
              }
            }
            break;

          case 'component_status':
            if (event.data) {
              // Track tool status for real-time display
              this.updateToolStatus(event.data);

              // Create/update test result for accessibility and performance tools
              if (event.data.tool === 'accessibility' || event.data.tool === 'performance') {
                if (event.data.status === 'completed') {
                  this.createOrUpdateTestResult(event.data);
                }
              }

              // Also update component status in workflow state
              if (event.data.testType) {
                this.workflowState().updateComponentStatus(
                  event.data.componentName,
                  event.data.testType,
                  event.data.status
                );
              }
            }
            break;

          case 'fix_suggestion':
            if (event.data) {
              // Add to workflow state
              this.workflowState().addFixSuggestion(event.data as FixSuggestion);

              // Update the corresponding test result with the suggested fix
              this.attachFixToTestResult(event.data as FixSuggestion);

              // Also track in timeline as a tool status
              this.updateToolStatus({
                componentName: event.data.componentName,
                tool: 'ai-suggestion',
                status: 'completed',
                message: `AI fix suggestion generated for ${event.data.testType} issues`,
                metadata: {
                  testType: event.data.testType,
                  violationCount: event.data.violations?.violationCount || 0,
                  severity: event.data.severity,
                },
                timestamp: event.data.timestamp,
                suggestion: event.data,
              });
            }
            break;

          case 'component-result':
          case 'component_result':
            if (event.data) {
              const data = event.data as any;

              // Handle rich component result (hyphenated event)
              if (data.result) {
                const result = data.result;
                const componentName = result.component.name;

                // Process accessibility results
                if (result.accessibility) {
                  const testResult: TestResult = {
                    componentName,
                    testType: 'accessibility',
                    status: result.accessibility.status === 'warning' ? 'serious' : result.accessibility.status as ComponentStatus,
                    timestamp: event.data.timestamp || Date.now(),
                    severity: result.accessibility.status === 'warning' ? 2 : 1,
                    a11yScore: result.accessibility.score,
                    violations: {
                      violationCount: result.accessibility.violations.total,
                      warningCount: 0, // Not explicitly separate in this new format usually
                      violations: [] // Mapping details to violations would be complex, we'll store details instead
                    },
                    details: result.accessibility.details,
                    metrics: undefined
                  };

                  // Update state
                  this.createOrUpdateTestResultFromRichEvent(testResult);
                }
              }
              // Handle legacy/simple component result (underscore event)
              else if (data.testResults && data.componentName) {
                const componentName = data.componentName;
                const status = data.status === 'accessibility-issues' ? 'failed' : 'passed';

                // We create a basic result just to show counts in the table if rich data hasn't arrived yet
                const testResult: TestResult = {
                  componentName,
                  testType: 'accessibility',
                  status: status as ComponentStatus,
                  timestamp: data.timestamp || Date.now(),
                  violations: {
                    violationCount: data.testResults.totalViolations || 0,
                    warningCount: 0,
                    violations: []
                  }
                };
                this.createOrUpdateTestResultFromRichEvent(testResult);
              }
            };
            break;

          case 'summary':
            if (event.data) {
              const summaryEvent = event as unknown as SSESummaryEvent;
              this.workflowState().setSummary(summaryEvent.summary);
            }
            break;

          case 'done':
          case 'completed':
            this.workflowState().setAnalyzing(false);
            this.progress.set(100);
            this.progressLabel.set('Complete');
            this.currentStatus.set('All tests completed');
            break;

          case 'error':
            this.workflowState().setAnalyzing(false);
            this.currentStatus.set('Error: ' + (event.data?.message || 'Unknown error'));
            break;
        }
      },
      error: (error) => {
        console.error('SSE error:', error);
        this.workflowState().setAnalyzing(false);
        this.currentStatus.set('Connection error');
      },
    });
  }

  isExpanded(key: string): boolean {
    return this.expandedRows().has(key);
  }

  toggleExpand(key: string): void {
    const expanded = new Set(this.expandedRows());
    if (expanded.has(key)) {
      expanded.delete(key);
    } else {
      expanded.add(key);
    }
    this.expandedRows.set(expanded);
  }

  getIssueCount(result: TestResult): string {
    if (result.testType === 'accessibility' && result.violations) {
      const violations = result.violations.violationCount || 0;
      const warnings = result.violations.warningCount || 0;
      return `${violations + warnings}`;
    }
    return '-';
  }

  getSeverityStatus(severity: number): ComponentStatus {
    if (severity === 1) return 'serious';
    if (severity === 2) return 'failed';
    return 'pending';
  }

  formatTimestamp(timestamp: number): string {
    return new Date(timestamp).toLocaleString();
  }

  formatBytes(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
  }

  goBack(): void {
    this.router().navigate(['/configure']);
  }

  exportResults(): void {
    const results = this.allResults();
    const dataStr = JSON.stringify(results, null, 2);
    const dataUri = 'data:application/json;charset=utf-8,' + encodeURIComponent(dataStr);

    const exportFileDefaultName = `test-results-${Date.now()}.json`;

    const linkElement = document.createElement('a');
    linkElement.setAttribute('href', dataUri);
    linkElement.setAttribute('download', exportFileDefaultName);
    linkElement.click();
  }

  startNewTest(): void {
    this.workflowState().reset();
    this.router().navigate(['/']);
  }

  private updateToolStatus(data: any): void {
    const componentName = data.componentName;
    const tool = data.tool;
    const status = data.status;

    this.componentToolStatus.update(map => {
      const tools = map.get(componentName) || [];

      // Find existing tool status
      const existingIndex = tools.findIndex(t => t.tool === tool);

      const toolStatus: ToolStatus = {
        tool,
        status,
        message: data.message || '',
        metadata: data.metadata,
        timestamp: data.timestamp || Date.now(),
        suggestion: data.suggestion,
      };

      if (existingIndex >= 0) {
        // Update existing tool status
        tools[existingIndex] = toolStatus;
      } else {
        // Add new tool status
        tools.push(toolStatus);
      }

      // Hack: If dev-server is starting, mark deployment as completed if it's stuck
      if (tool === 'dev-server' && status === 'starting') {
        const deploymentIndex = tools.findIndex(t => t.tool === 'deployment');
        if (deploymentIndex >= 0 && tools[deploymentIndex].status !== 'completed') {
          tools[deploymentIndex] = {
            ...tools[deploymentIndex],
            status: 'completed',
            message: 'Deployment completed'
          };
        }
      }

      map.set(componentName, tools);
      return new Map(map);
    });
  }

  private calculateOverallStatus(results: TestResult[], tools: ToolStatus[]): ComponentStatus {
    // If we have explicit results with failures, that takes precedence
    if (results.some(r => r.status === 'failed')) return 'failed';
    if (results.some(r => r.status === 'serious')) return 'serious';

    // Check if any tools are still running ONLY if we don't have a definitive result yet
    // Or if the specific tool for a missing result is running
    const hasRunningTools = tools.some(t =>
      t.status === 'starting' || t.status === 'in-progress'
    );

    if (hasRunningTools && results.length === 0) return 'running';
    if (results.length > 0) {
      // If all existing results are passed, but tests are still running, show running?
      // Actually, if we have results, we should probably show the worst status of the results
      // unless a totally new test type is running.
      // For now, let's assume if we have results, we show their status.
      const worstStatus = this.getWorstStatus(results);
      if (worstStatus === 'passed' && hasRunningTools) return 'running';
      return worstStatus;
    }

    return 'pending';
  }

  private getWorstStatus(results: TestResult[]): ComponentStatus {
    if (results.some(r => r.status === 'failed')) return 'failed';
    if (results.some(r => r.status === 'serious')) return 'serious';
    if (results.some(r => r.status === 'pending')) return 'pending';
    return 'passed';
  }

  private calculateTotalIssues(results: TestResult[]): number {
    let total = 0;
    results.forEach(result => {
      if (result.testType === 'accessibility' && result.violations) {
        total += (result.violations.violationCount || 0) + (result.violations.warningCount || 0);
      }
    });
    return total;
  }

  getCompletedComponentsCount(): number {
    return this.groupedResults().filter(component =>
      component.results.every(r => r.status === 'passed' || r.status === 'failed' || r.status === 'serious')
    ).length;
  }

  getToolBadgeStatus(status: string): ComponentStatus {
    switch (status) {
      case 'completed':
      case 'stopped':
        return 'passed';
      case 'failed':
        return 'failed';
      case 'starting':
      case 'in-progress':
        return 'running';
      default:
        return 'pending';
    }
  }

  formatToolName(tool: string): string {
    // Format tool names for better readability
    const nameMap: { [key: string]: string } = {
      'dev-server': 'Dev Server',
      'metadata': 'Metadata Extraction',
      'harness': 'Test Harness',
      'accessibility': 'Accessibility Test',
      'performance': 'Performance Test',
      'ai-suggestion': 'AI Fix Suggestion',
    };

    return nameMap[tool] || tool.split('-').map(word =>
      word.charAt(0).toUpperCase() + word.slice(1)
    ).join(' ');
  }

  getSummaryStatus(status: string | undefined): ComponentStatus {
    if (!status) return 'pending';
    if (status === 'warning') return 'serious';
    // Validate if it is a valid ComponentStatus, otherwise default to pending or pass through if distinct
    const validStatuses: ComponentStatus[] = ['passed', 'failed', 'running', 'pending', 'serious'];
    if (validStatuses.includes(status as ComponentStatus)) {
      return status as ComponentStatus;
    }
    return 'pending';
  }

  private createOrUpdateTestResult(data: any): void {
    const componentName = data.componentName;
    const testType = data.testType as TestType;
    const metadata = data.metadata || {};

    // Create or update test result
    const existingResults = this.workflowState().results();
    const existingIndex = existingResults.findIndex(
      r => r.componentName === componentName && r.testType === testType
    );

    // Determine status based on metadata
    let status: ComponentStatus = 'passed';
    if (metadata.violationCount > 0 || (metadata.violations && metadata.violations.length > 0)) {
      status = 'failed';
    }
    if (metadata.performanceScore && metadata.performanceScore < 50) {
      status = 'serious';
    }

    const testResult: TestResult = {
      componentName,
      testType,
      status,
      timestamp: data.timestamp || Date.now(),
      violations: testType === 'accessibility' ? {
        violationCount: metadata.violationCount || 0,
        warningCount: metadata.warningCount || 0,
        violations: metadata.violations || [],
      } : undefined,
      metrics: testType === 'performance' ? {
        loadTime: metadata.loadTime || 0,
        renderTime: metadata.renderTime || 0,
        tti: metadata.tti || 0,
        performanceScore: metadata.performanceScore || 0,
        bundleSize: metadata.bundleSize,
        memoryUsage: metadata.memoryUsage,
        fps: metadata.fps,
      } : undefined,
    };

    // Update or add the test result
    if (existingIndex >= 0) {
      const updatedResults = [...existingResults];
      updatedResults[existingIndex] = testResult;
      this.workflowState().setResults(updatedResults);
    } else {
      this.workflowState().setResults([...existingResults, testResult]);
    }
  }

  private attachFixToTestResult(suggestion: FixSuggestion): void {
    const existingResults = this.workflowState().results();
    const existingIndex = existingResults.findIndex(
      r => r.componentName === suggestion.componentName && r.testType === suggestion.testType
    );

    if (existingIndex >= 0) {
      // Update existing test result with suggested fix and violations from suggestion
      const updatedResults = [...existingResults];
      updatedResults[existingIndex] = {
        ...updatedResults[existingIndex],
        suggestedFix: suggestion.suggestedFix,
        explanation: suggestion.explanation,
        filePath: suggestion.filePath,
        severity: suggestion.severity,
        // Update violations with detailed data from suggestion
        violations: suggestion.testType === 'accessibility' ? {
          violationCount: suggestion.violations?.violationCount || 0,
          warningCount: suggestion.violations?.warningCount || 0,
          violations: suggestion.violations?.violations || [],
        } : updatedResults[existingIndex].violations,
      };
      this.workflowState().setResults(updatedResults);
    } else {
      // Create test result from suggestion if it doesn't exist yet
      const violationCount = suggestion.violations?.violationCount ?? 0;
      const status: ComponentStatus = violationCount > 0 ? 'failed' : 'passed';

      const testResult: TestResult = {
        componentName: suggestion.componentName,
        testType: suggestion.testType,
        status,
        timestamp: suggestion.timestamp,
        violations: suggestion.testType === 'accessibility' ? {
          violationCount: violationCount,
          warningCount: suggestion.violations?.warningCount ?? 0,
          violations: suggestion.violations?.violations ?? [],
        } : undefined,
        metrics: suggestion.testType === 'performance' ? suggestion.violations?.metrics : undefined,
        suggestedFix: suggestion.suggestedFix,
        explanation: suggestion.explanation,
        filePath: suggestion.filePath,
        severity: suggestion.severity,
      };

      this.workflowState().setResults([...existingResults, testResult]);
    }
  }


  private createOrUpdateTestResultFromRichEvent(testResult: TestResult): void {
    const existingResults = this.workflowState().results();
    const existingIndex = existingResults.findIndex(
      r => r.componentName === testResult.componentName && r.testType === testResult.testType
    );

    if (existingIndex >= 0) {
      const updatedResults = [...existingResults];
      // Merge with existing, keeping what we can
      updatedResults[existingIndex] = {
        ...updatedResults[existingIndex],
        ...testResult,
        status: testResult.status // Ensure status is updated
      };
      this.workflowState().setResults(updatedResults);
    } else {
      this.workflowState().setResults([...existingResults, testResult]);
    }
  }
}
