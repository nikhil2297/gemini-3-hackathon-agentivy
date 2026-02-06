import { Component, input, output, ChangeDetectionStrategy } from '@angular/core';
import { StatusBadgeComponent } from '../../../../shared/components/status-badge/status-badge.component';
import { ButtonComponent } from '../../../../shared/components/button/button.component';
import { SSESummaryEvent, ComponentStatus } from '../../../../core/models/api.models';
import { mapSummaryStatus } from '../../../../shared/utils/formatters';

@Component({
  selector: 'app-results-header',
  standalone: true,
  imports: [StatusBadgeComponent, ButtonComponent],
  templateUrl: './results-header.component.html',
  styleUrl: './results-header.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResultsHeaderComponent {
  repoUrl = input<string>('');
  componentCount = input<number>(0);
  summary = input<SSESummaryEvent['summary'] | undefined>(undefined);

  goBack = output<void>();

  protected readonly getSummaryStatus = mapSummaryStatus;
}
