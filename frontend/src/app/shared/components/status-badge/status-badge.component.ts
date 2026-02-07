import { Component, ChangeDetectionStrategy, input, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TestStatus } from '../../../core/models/api.models';

export type BadgeVariant = 'passed' | 'failed' | 'running' | 'pending' | 'serious';

/**
 * Status Badge Component with Claude theme styling
 * Displays test status with appropriate color coding
 */
@Component({
  selector: 'app-status-badge',
  imports: [CommonModule],
  templateUrl: './status-badge.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusBadgeComponent {
  /**
   * Badge status variant
   */
  status = input.required<BadgeVariant>();

  /**
   * Show icon before text
   */
  showIcon = input<boolean>(true);

  /**
   * Custom text (overrides default status text)
   */
  customText = input<string>('');

  /**
   * Computed CSS classes
   */
  classes = computed(() => {
    const classes = ['badge'];
    classes.push(`badge--${this.status()}`);
    return classes.join(' ');
  });

  /**
   * Computed display text
   */
  displayText = computed(() => {
    if (this.customText()) {
      return this.customText();
    }

    const statusMap: Record<BadgeVariant, string> = {
      passed: 'Passed',
      failed: 'Failed',
      running: 'Running',
      pending: 'Pending',
      serious: 'Serious',
    };

    return statusMap[this.status()];
  });

  /**
   * Get icon for status
   */
  getIcon = computed(() => {
    if (!this.showIcon()) return '';

    const iconMap: Record<BadgeVariant, string> = {
      passed: '✓',
      failed: '✕',
      running: '⟳',
      pending: '○',
      serious: '⚠',
    };

    return iconMap[this.status()];
  });
}
