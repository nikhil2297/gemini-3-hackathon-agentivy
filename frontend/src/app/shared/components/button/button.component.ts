import { Component, ChangeDetectionStrategy, input, output, computed } from '@angular/core';
import { CommonModule } from '@angular/common';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost';
export type ButtonSize = 'sm' | 'md' | 'lg';

/**
 * Reusable Button Component with Claude theme styling
 * Supports multiple variants and sizes with full accessibility
 */
@Component({
  selector: 'app-button',
  imports: [CommonModule],
  templateUrl: './button.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[attr.disabled]': 'disabled() ? true : null',
  },
})
export class ButtonComponent {
  /**
   * Button variant style
   */
  variant = input<ButtonVariant>('primary');

  /**
   * Button size
   */
  size = input<ButtonSize>('md');

  /**
   * Disabled state
   */
  disabled = input<boolean>(false);

  /**
   * Full width button
   */
  fullWidth = input<boolean>(false);

  /**
   * Loading state (shows spinner)
   */
  loading = input<boolean>(false);

  /**
   * Button type attribute
   */
  type = input<'button' | 'submit' | 'reset'>('button');

  /**
   * Click event emitter
   */
  clicked = output<void>();

  /**
   * Computed CSS classes
   */
  classes = computed(() => {
    const classes = ['button'];

    // Add variant class
    classes.push(`button--${this.variant()}`);

    // Add size class
    classes.push(`button--${this.size()}`);

    // Add state classes
    if (this.disabled() || this.loading()) {
      classes.push('button--disabled');
    }

    if (this.fullWidth()) {
      classes.push('button--full-width');
    }

    if (this.loading()) {
      classes.push('button--loading');
    }

    return classes.join(' ');
  });

  /**
   * Handle button click
   */
  handleClick(): void {
    if (!this.disabled() && !this.loading()) {
      this.clicked.emit();
    }
  }
}
