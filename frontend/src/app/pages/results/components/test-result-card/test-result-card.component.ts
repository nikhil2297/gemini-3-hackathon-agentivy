import { Component, input, ChangeDetectionStrategy } from '@angular/core';
import { StatusBadgeComponent } from '../../../../shared/components/status-badge/status-badge.component';
import { CodeBlockComponent } from '../../../../shared/components/code-block/code-block.component';
import { AccessibilityViolationsComponent } from '../accessibility-violations/accessibility-violations.component';
import { AccessibilityDetailsComponent } from '../accessibility-details/accessibility-details.component';
import { PerformanceMetricsComponent } from '../performance-metrics/performance-metrics.component';
import { TestResult } from '../../../../core/models/api.models';

@Component({
  selector: 'app-test-result-card',
  standalone: true,
  imports: [
    StatusBadgeComponent,
    CodeBlockComponent,
    AccessibilityViolationsComponent,
    AccessibilityDetailsComponent,
    PerformanceMetricsComponent,
  ],
  templateUrl: './test-result-card.component.html',
  styleUrl: './test-result-card.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TestResultCardComponent {
  result = input.required<TestResult>();

  get hasLegacyViolations(): boolean {
    const r = this.result();
    return r.testType === 'accessibility' &&
      (!r.details || r.details.length === 0) &&
      !!r.violations?.violations &&
      r.violations.violations.length > 0;
  }

  get hasRichDetails(): boolean {
    const r = this.result();
    return r.testType === 'accessibility' &&
      !!r.details &&
      r.details.length > 0;
  }

  get hasPerformanceMetrics(): boolean {
    const r = this.result();
    return r.testType === 'performance' && !!r.metrics;
  }

  get isEmptySuccess(): boolean {
    const r = this.result();
    return r.status === 'passed' &&
      (!r.violations || (r.violations.violationCount === 0 && r.violations.warningCount === 0)) &&
      (!r.details || r.details.length === 0) &&
      (!r.metrics || r.testType !== 'performance') &&
      !r.suggestedFix &&
      !r.errorMessage;
  }
}
