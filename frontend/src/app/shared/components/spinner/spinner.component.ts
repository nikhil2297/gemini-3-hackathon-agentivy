import { Component, ChangeDetectionStrategy, input } from '@angular/core';
import { CommonModule } from '@angular/common';

export type SpinnerVariant = 'typing' | 'circular';
export type SpinnerSize = 'sm' | 'md' | 'lg';

/**
 * Spinner/Loading Component with Claude theme styling
 * Supports typing indicator (3 dots) and circular spinner
 */
@Component({
  selector: 'app-spinner',
  imports: [CommonModule],
  templateUrl: './spinner.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SpinnerComponent {
  /**
   * Spinner variant
   */
  variant = input<SpinnerVariant>('circular');

  /**
   * Spinner size
   */
  size = input<SpinnerSize>('md');

  /**
   * Optional message to display
   */
  message = input<string>('');
}
