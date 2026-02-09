import { Component, input } from '@angular/core';
import { PerformanceMetrics } from '../../../../core/models/api.models';
import { formatBytes } from '../../../../shared/utils/formatters';

@Component({
  selector: 'app-performance-metrics',
  standalone: true,
  templateUrl: './performance-metrics.component.html',
  styleUrl: './performance-metrics.component.css',
})
export class PerformanceMetricsComponent {
  metrics = input.required<PerformanceMetrics>();

  protected readonly formatBytes = formatBytes;
}
