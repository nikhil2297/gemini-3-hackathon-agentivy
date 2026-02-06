import { Component, computed, signal, inject, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { SpinnerComponent } from '../../shared/components/spinner/spinner.component';
import { WorkflowStateService } from '../../core/services/state/workflow-state.service';
import { AnalysisService } from '../../core/services/api/analysis.service';
import { SseEventProcessorService } from '../../core/services/sse/sse-event-processor.service';
import { TestResult } from '../../core/models/api.models';
import { downloadJson } from '../../shared/utils/export';
import { ResultsHeaderComponent } from './components/results-header/results-header.component';
import { ToolTimelineComponent } from './components/tool-timeline/tool-timeline.component';
import { TestResultCardComponent } from './components/test-result-card/test-result-card.component';
import { ResultsFooterComponent } from './components/results-footer/results-footer.component';

@Component({
  selector: 'app-results',
  standalone: true,
  imports: [
    CommonModule,
    StatusBadgeComponent,
    SpinnerComponent,
    ResultsHeaderComponent,
    ToolTimelineComponent,
    TestResultCardComponent,
    ResultsFooterComponent,
  ],
  templateUrl: './results.component.html',
  styleUrl: './results.component.css',
})
export class ResultsComponent implements OnInit, OnDestroy {
  private readonly workflowState = inject(WorkflowStateService);
  private readonly analysisService = inject(AnalysisService);
  private readonly router = inject(Router);
  protected readonly sseProcessor = inject(SseEventProcessorService);

  private expandedRows = signal<Set<string>>(new Set());

  // Delegate state to services
  repoInfo = computed(() => this.workflowState.repoInfo());
  selectedComponents = computed(() => this.workflowState.selectedComponents());
  selectedTests = computed(() => this.workflowState.selectedTests());
  actionMode = computed(() => this.workflowState.actionMode());
  isAnalyzing = computed(() => this.workflowState.isAnalyzing());
  allResults = computed(() => this.workflowState.results());
  summary = computed(() => this.workflowState.summary());


  ngOnInit(): void {
    if (!this.repoInfo() || this.selectedComponents().length === 0) {
      this.router.navigate(['/']);
      return;
    }

    this.startAnalysis();
  }

  ngOnDestroy(): void {
    this.sseProcessor.stopListening();
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

    const selectedComponentNames = this.selectedComponents();
    const selectedComponentObjects = (repoInfo.discoveredComponents || [])
      .filter(c => selectedComponentNames.includes(c.name));

    this.analysisService.generateReport({
      repoPath: repoInfo.localPath,
      repoId: repoInfo.repoId,
      component: selectedComponentObjects,
      tests: this.selectedTests(),
    }).subscribe({
      next: (response) => {
        this.workflowState.setResults(response.results);
        this.workflowState.setAnalyzing(false);
        this.sseProcessor.progress.set(100);
        this.sseProcessor.progressLabel.set('Complete');
      },
      error: (error) => {
        console.error('Analysis error:', error);
        this.workflowState.setAnalyzing(false);
        this.sseProcessor.currentStatus.set('Error: ' + (error.error?.message || 'Analysis failed'));
      },
    });
  }

  private startSuggestFixes(): void {
    const repoInfo = this.repoInfo();
    if (!repoInfo) return;

    const selectedComponentNames = this.selectedComponents();
    const selectedComponentObjects = (repoInfo.discoveredComponents || [])
      .filter(c => selectedComponentNames.includes(c.name));

    this.sseProcessor.startSuggestFixes({
      repoPath: repoInfo.localPath,
      repoId: repoInfo.repoId,
      component: selectedComponentObjects,
      tests: this.selectedTests(),
    });
  }

  // --- UI interaction methods ---

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

  goBack(): void {
    this.router.navigate(['/configure']);
  }

  exportResults(): void {
    downloadJson(this.allResults(), 'test-results');
  }

  startNewTest(): void {
    this.workflowState.reset();
    this.sseProcessor.reset();
    this.router.navigate(['/']);
  }
}
