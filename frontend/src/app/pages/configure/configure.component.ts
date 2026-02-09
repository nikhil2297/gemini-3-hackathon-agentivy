import { Component, computed, signal, OnInit, inject } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { WorkflowStateService } from '../../core/services/state/workflow-state.service';
import { TestType } from '../../core/models/api.models';

@Component({
  selector: 'app-configure',
  standalone: true,
  imports: [CommonModule, ButtonComponent],
  templateUrl: './configure.component.html',
  styleUrl: './configure.component.css',
})
export class ConfigureComponent implements OnInit {
  private readonly workflowState = inject(WorkflowStateService);
  private readonly router = inject(Router);

  repoInfo = computed(() => this.workflowState.repoInfo());
  selectedComponents = computed(() => this.workflowState.selectedComponents());
  selectedTests = computed(() => this.workflowState.selectedTests());

  availableComponents = computed(() => {
    const all = this.repoInfo()?.availableComponents || [];
    const selected = this.selectedComponents();
    return all.filter((c: string) => !selected.includes(c));
  });

  testTypes = [
    {
      value: 'accessibility' as TestType,
      label: 'Accessibility Testing',
      description: 'Check for WCAG compliance and accessibility violations',
    },
    {
      value: 'performance' as TestType,
      label: 'Performance Testing',
      description: 'Analyze render time, bundle size, and performance metrics',
    },
  ];

  canStartTesting = computed(() => {
    return this.selectedComponents().length > 0 && this.selectedTests().length > 0;
  });

  ngOnInit(): void {
    // Redirect to home if no repo info
    if (!this.repoInfo()) {
      this.router.navigate(['/']);
    }
  }

  selectComponent(component: string): void {
    this.workflowState.addComponent(component);
  }

  deselectComponent(component: string): void {
    this.workflowState.removeComponent(component);
  }

  isTestTypeSelected(testType: TestType): boolean {
    return this.selectedTests().includes(testType);
  }

  toggleTestType(testType: TestType): void {
    this.workflowState.toggleTest(testType);
  }

  goBack(): void {
    this.router.navigate(['/']);
  }

  startTesting(): void {
    if (!this.canStartTesting()) return;

    this.workflowState.setActionMode('suggest');
    this.workflowState.setAnalyzing(true);
    this.router.navigate(['/results']);
  }
}
