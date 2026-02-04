import { Component, ChangeDetectionStrategy, input, computed } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Progress Bar Component with Claude theme styling
 * Displays progress with smooth animations
 */
@Component({
  selector: 'app-progress-bar',
  imports: [CommonModule],
  templateUrl: './progress-bar.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProgressBarComponent {
  /**
   * Progress value (0-100)
   */
  value = input<number>(0);

  /**
   * Show percentage text
   */
  showPercentage = input<boolean>(true);

  /**
   * Progress label
   */
  label = input<string>('');

  /**
   * Indeterminate mode (animated infinite progress)
   */
  indeterminate = input<boolean>(false);

  /**
   * Computed percentage value (clamped 0-100)
   */
  percentage = computed(() => {
    const val = this.value();
    return Math.max(0, Math.min(100, val));
  });

  /**
   * Computed width style
   */
  widthStyle = computed(() => {
    if (this.indeterminate()) {
      return '30%';
    }
    return `${this.percentage()}%`;
  });
}
