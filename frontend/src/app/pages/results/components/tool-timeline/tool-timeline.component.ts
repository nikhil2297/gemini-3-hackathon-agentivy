import { Component, input } from '@angular/core';
import { SpinnerComponent } from '../../../../shared/components/spinner/spinner.component';
import { ToolStatus } from '../../../../core/models/api.models';
import { formatToolName } from '../../../../shared/utils/formatters';

@Component({
  selector: 'app-tool-timeline',
  standalone: true,
  imports: [SpinnerComponent],
  templateUrl: './tool-timeline.component.html',
  styleUrl: './tool-timeline.component.css',
})
export class ToolTimelineComponent {
  tools = input.required<ToolStatus[]>();

  protected readonly formatToolName = formatToolName;
}
