import { Component, input } from '@angular/core';
import { Violation } from '../../../../core/models/api.models';

@Component({
  selector: 'app-accessibility-violations',
  standalone: true,
  templateUrl: './accessibility-violations.component.html',
  styleUrl: './accessibility-violations.component.css',
})
export class AccessibilityViolationsComponent {
  violations = input.required<Violation[]>();
}
