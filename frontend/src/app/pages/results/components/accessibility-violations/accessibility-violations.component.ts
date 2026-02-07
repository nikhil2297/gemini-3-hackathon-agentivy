import { Component, input, ChangeDetectionStrategy } from '@angular/core';
import { Violation } from '../../../../core/models/api.models';

@Component({
  selector: 'app-accessibility-violations',
  standalone: true,
  templateUrl: './accessibility-violations.component.html',
  styleUrl: './accessibility-violations.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccessibilityViolationsComponent {
  violations = input.required<Violation[]>();
}
