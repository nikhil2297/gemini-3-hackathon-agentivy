import { Component, input, output } from '@angular/core';
import { ButtonComponent } from '../../../../shared/components/button/button.component';

@Component({
  selector: 'app-results-footer',
  standalone: true,
  imports: [ButtonComponent],
  templateUrl: './results-footer.component.html',
  styleUrl: './results-footer.component.css',
})
export class ResultsFooterComponent {
  isAnalyzing = input<boolean>(false);
  hasResults = input<boolean>(false);

  exportResults = output<void>();
  startNewTest = output<void>();
}
