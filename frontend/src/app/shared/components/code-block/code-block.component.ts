import { Component, ChangeDetectionStrategy, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Code Block Component with Claude theme styling
 * Displays code with syntax highlighting and copy functionality
 */
@Component({
  selector: 'app-code-block',
  imports: [CommonModule],
  templateUrl: './code-block.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CodeBlockComponent {
  /**
   * Code content to display
   */
  code = input.required<string>();

  /**
   * Programming language for syntax highlighting
   */
  language = input<string>('typescript');

  /**
   * Show line numbers
   */
  showLineNumbers = input<boolean>(false);

  /**
   * Show copy button
   */
  showCopyButton = input<boolean>(true);

  /**
   * Max height before scrolling
   */
  maxHeight = input<string>('400px');

  /**
   * Copied state
   */
  protected copied = signal<boolean>(false);

  /**
   * Copy code to clipboard
   */
  async copyToClipboard(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.code());
      this.copied.set(true);

      // Reset copied state after 2 seconds
      setTimeout(() => {
        this.copied.set(false);
      }, 2000);
    } catch (error) {
      console.error('Failed to copy code:', error);
    }
  }

  /**
   * Get code lines for line numbers
   */
  getCodeLines(): string[] {
    return this.code().split('\n');
  }
}
