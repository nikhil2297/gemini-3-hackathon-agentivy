import { Injectable, inject, signal, computed } from '@angular/core';
import { Subscription } from 'rxjs';
import { WorkflowStateService } from '../state/workflow-state.service';
import { SseService } from './sse.service';
import {
  TestResult,
  FixSuggestion,
  ComponentStatus,
  SuggestFixConfig,
  TestType,
  SSESummaryEvent,
  ToolStatus,
  GroupedComponentResult,
} from '../../models/api.models';

@Injectable({
  providedIn: 'root',
})
export class SseEventProcessorService {
  private readonly workflowState = inject(WorkflowStateService);
  private readonly sseService = inject(SseService);

  private sseSubscription?: Subscription;

  // UI-facing signals
  readonly progress = signal(0);
  readonly progressLabel = signal('Initializing...');
  readonly currentStatus = signal('');
  readonly componentToolStatus = signal<Map<string, ToolStatus[]>>(new Map());

  // Group results by component name and include tool statuses
  readonly groupedResults = computed<GroupedComponentResult[]>(() => {
    const toolStatuses = this.componentToolStatus();
    const results = this.workflowState.results();

    const componentNames = new Set<string>();
    toolStatuses.forEach((_, componentName) => componentNames.add(componentName));
    results.forEach(result => componentNames.add(result.componentName));

    return Array.from(componentNames).map(componentName => {
      const componentResults = results.filter(r => r.componentName === componentName && r.testType != undefined);
      const tools = toolStatuses.get(componentName) || [];
      const sortedTools = tools.sort((a, b) => a.timestamp - b.timestamp);
      const overallStatus = this.calculateOverallStatus(componentResults, sortedTools);
      const totalIssues = this.calculateTotalIssues(componentResults);

      return {
        componentName,
        results: componentResults,
        tools: sortedTools,
        overallStatus,
        totalIssues,
      };
    });
  });

  startSuggestFixes(config: SuggestFixConfig): void {
    this.sseSubscription = this.sseService.connectToSuggestFixes(config).subscribe({
      next: (event) => {
        switch (event.type) {
          case 'started':
            this.handleStarted();
            break;
          case 'progress':
            this.handleProgress(event.data);
            break;
          case 'component_status':
            this.handleComponentStatus(event.data);
            break;
          case 'fix_suggestion':
            this.handleFixSuggestion(event.data);
            break;
          case 'component-result':
          case 'component_result':
            this.handleComponentResult(event.data);
            break;
          case 'summary':
            this.handleSummary(event);
            break;
          case 'done':
          case 'completed':
            this.handleCompleted();
            break;
          case 'error':
            this.handleError(event.data);
            break;
        }
      },
      error: (error) => {
        console.error('SSE error:', error);
        this.workflowState.setAnalyzing(false);
        this.currentStatus.set('Connection error');
      },
    });
  }

  stopListening(): void {
    this.sseSubscription?.unsubscribe();
  }

  reset(): void {
    this.progress.set(0);
    this.progressLabel.set('Initializing...');
    this.currentStatus.set('');
    this.componentToolStatus.set(new Map());
  }

  getCompletedComponentsCount(): number {
    return this.groupedResults().filter(component =>
      component.results.every(r => r.status === 'passed' || r.status === 'failed' || r.status === 'serious')
    ).length;
  }

  // --- Private event handlers ---

  private handleStarted(): void {
    this.progressLabel.set('Analysis started');
    this.currentStatus.set('Connecting to server...');
  }

  private handleProgress(data: any): void {
    if (!data) return;
    this.progress.set(data.progress || 0);
    this.currentStatus.set(data.message || '');
    if (data.message) {
      this.progressLabel.set(data.message);
    }
  }

  private handleComponentStatus(data: any): void {
    if (!data) return;

    this.updateToolStatus(data);

    if (data.tool === 'accessibility' || data.tool === 'performance') {
      if (data.status === 'completed') {
        this.createOrUpdateTestResult(data);
      }
    }

    if (data.testType) {
      this.workflowState.updateComponentStatus(
        data.componentName,
        data.testType,
        data.status
      );
    }
  }

  private handleFixSuggestion(data: any): void {
    if (!data) return;

    this.workflowState.addFixSuggestion(data as FixSuggestion);
    this.attachFixToTestResult(data as FixSuggestion);

    this.updateToolStatus({
      componentName: data.componentName,
      tool: 'ai-suggestion',
      status: 'completed',
      message: `AI fix suggestion generated for ${data.testType} issues`,
      metadata: {
        testType: data.testType,
        violationCount: data.violations?.violationCount || 0,
        severity: data.severity,
      },
      timestamp: data.timestamp,
      suggestion: data,
    });
  }

  private handleComponentResult(data: any): void {
    if (!data) return;

    if (data.result) {
      const result = data.result;
      const componentName = result.component.name;

      if (result.accessibility) {
        const testResult: TestResult = {
          componentName,
          testType: 'accessibility',
          status: result.accessibility.status === 'warning' ? 'serious' : result.accessibility.status as ComponentStatus,
          timestamp: data.timestamp || Date.now(),
          severity: result.accessibility.status === 'warning' ? 2 : 1,
          a11yScore: result.accessibility.score,
          violations: {
            violationCount: result.accessibility.violations.total,
            warningCount: 0,
            violations: []
          },
          details: result.accessibility.details,
          metrics: undefined
        };

        this.createOrUpdateTestResultFromRichEvent(testResult);
      }
    } else if (data.testResults && data.componentName) {
      const componentName = data.componentName;
      const status = data.status === 'accessibility-issues' ? 'failed' : 'passed';

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
  }

  private handleSummary(event: any): void {
    if (!event.data) return;
    const summaryEvent = event as unknown as SSESummaryEvent;
    this.workflowState.setSummary(summaryEvent.summary);
  }

  private handleCompleted(): void {
    this.workflowState.setAnalyzing(false);
    this.progress.set(100);
    this.progressLabel.set('Complete');
    this.currentStatus.set('All tests completed');
  }

  private handleError(data: any): void {
    this.workflowState.setAnalyzing(false);
    this.currentStatus.set('Error: ' + (data?.message || 'Unknown error'));
  }

  // --- Private helpers ---

  private updateToolStatus(data: any): void {
    const componentName = data.componentName;
    const tool = data.tool;
    const status = data.status;

    this.componentToolStatus.update(map => {
      const tools = map.get(componentName) || [];
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
        tools[existingIndex] = toolStatus;
      } else {
        tools.push(toolStatus);
      }

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
    if (results.some(r => r.status === 'failed')) return 'failed';
    if (results.some(r => r.status === 'serious')) return 'serious';

    const hasRunningTools = tools.some(t =>
      t.status === 'starting' || t.status === 'in-progress'
    );

    if (hasRunningTools && results.length === 0) return 'running';
    if (results.length > 0) {
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

  private createOrUpdateTestResult(data: any): void {
    const componentName = data.componentName;
    const testType = data.testType as TestType;
    const metadata = data.metadata || {};

    const existingResults = this.workflowState.results();
    const existingIndex = existingResults.findIndex(
      r => r.componentName === componentName && r.testType === testType
    );

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

    if (existingIndex >= 0) {
      const updatedResults = [...existingResults];
      updatedResults[existingIndex] = testResult;
      this.workflowState.setResults(updatedResults);
    } else {
      this.workflowState.setResults([...existingResults, testResult]);
    }
  }

  private attachFixToTestResult(suggestion: FixSuggestion): void {
    const existingResults = this.workflowState.results();
    const existingIndex = existingResults.findIndex(
      r => r.componentName === suggestion.componentName && r.testType === suggestion.testType
    );

    if (existingIndex >= 0) {
      const updatedResults = [...existingResults];
      updatedResults[existingIndex] = {
        ...updatedResults[existingIndex],
        suggestedFix: suggestion.suggestedFix,
        explanation: suggestion.explanation,
        filePath: suggestion.filePath,
        severity: suggestion.severity,
        violations: suggestion.testType === 'accessibility' ? {
          violationCount: suggestion.violations?.violationCount || 0,
          warningCount: suggestion.violations?.warningCount || 0,
          violations: suggestion.violations?.violations || [],
        } : updatedResults[existingIndex].violations,
      };
      this.workflowState.setResults(updatedResults);
    } else {
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

      this.workflowState.setResults([...existingResults, testResult]);
    }
  }

  private createOrUpdateTestResultFromRichEvent(testResult: TestResult): void {
    const existingResults = this.workflowState.results();
    const existingIndex = existingResults.findIndex(
      r => r.componentName === testResult.componentName && r.testType === testResult.testType
    );

    if (existingIndex >= 0) {
      const updatedResults = [...existingResults];
      updatedResults[existingIndex] = {
        ...updatedResults[existingIndex],
        ...testResult,
        status: testResult.status
      };
      this.workflowState.setResults(updatedResults);
    } else {
      this.workflowState.setResults([...existingResults, testResult]);
    }
  }
}
