import {
  Component,
  ChangeDetectionStrategy,
  input,
  output,
  computed,
  signal,
  forwardRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

export type InputType = 'text' | 'email' | 'url' | 'password' | 'number';

/**
 * Reusable Input Component with Claude theme styling
 * Implements ControlValueAccessor for form integration
 */
@Component({
  selector: 'app-input',
  imports: [CommonModule],
  templateUrl: './input.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => InputComponent),
      multi: true,
    },
  ],
})
export class InputComponent implements ControlValueAccessor {
  /**
   * Input label
   */
  label = input<string>('');

  /**
   * Input placeholder
   */
  placeholder = input<string>('');

  /**
   * Input type
   */
  type = input<InputType>('text');

  /**
   * Error message
   */
  error = input<string | null>(null);

  /**
   * Helper text
   */
  helperText = input<string>('');

  /**
   * Required field
   */
  required = input<boolean>(false);

  /**
   * Disabled state
   */
  disabled = input<boolean>(false);

  /**
   * Value changed event
   */
  valueChange = output<string>();

  /**
   * Internal value state
   */
  protected value = signal<string>('');

  /**
   * Touched state
   */
  protected touched = signal<boolean>(false);

  /**
   * Focused state
   */
  protected focused = signal<boolean>(false);

  /**
   * Computed CSS classes for input field
   */
  inputClasses = computed(() => {
    const classes = ['input-field'];

    if (this.error()) {
      classes.push('input-field--error');
    }

    if (this.focused()) {
      classes.push('input-field--focused');
    }

    return classes.join(' ');
  });

  /**
   * Computed classes for wrapper
   */
  wrapperClasses = computed(() => {
    const classes = ['input-wrapper'];

    if (this.disabled()) {
      classes.push('input-wrapper--disabled');
    }

    return classes.join(' ');
  });

  // ControlValueAccessor implementation
  private onChange: (value: string) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(value: string): void {
    this.value.set(value || '');
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    // Disabled state is handled via input signal
  }

  /**
   * Handle input change
   */
  onInputChange(event: Event): void {
    const target = event.target as HTMLInputElement;
    const value = target.value;
    this.value.set(value);
    this.onChange(value);
    this.valueChange.emit(value);
  }

  /**
   * Handle input blur
   */
  onBlur(): void {
    this.touched.set(true);
    this.focused.set(false);
    this.onTouched();
  }

  /**
   * Handle input focus
   */
  onFocus(): void {
    this.focused.set(true);
  }

  /**
   * Generate unique ID for label association
   */
  protected readonly inputId = `input-${Math.random().toString(36).substr(2, 9)}`;
}
