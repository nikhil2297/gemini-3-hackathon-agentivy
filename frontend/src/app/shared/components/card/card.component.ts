import { Component, ChangeDetectionStrategy, input } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Reusable Card Component with Claude theme styling
 * Supports optional header, body, and footer sections
 */
@Component({
  selector: 'app-card',
  imports: [CommonModule],
  templateUrl: './card.component.html',
  styleUrl: './card.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CardComponent {
  /**
   * Card title (displayed in header)
   */
  title = input<string>('');

  /**
   * Card subtitle (displayed in header)
   */
  subtitle = input<string>('');

  /**
   * Remove padding from body
   */
  noPadding = input<boolean>(false);
}
