import { Component, input, ChangeDetectionStrategy } from '@angular/core';
import { CodeBlockComponent } from '../../../../shared/components/code-block/code-block.component';
import { ResultIssue } from '../../../../core/models/api.models';

@Component({
  selector: 'app-accessibility-details',
  standalone: true,
  imports: [CodeBlockComponent],
  templateUrl: './accessibility-details.component.html',
  styleUrl: './accessibility-details.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccessibilityDetailsComponent {
  details = input.required<{ file: string; issues: ResultIssue[] }[]>();
}
