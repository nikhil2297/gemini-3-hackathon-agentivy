import { Component, computed, signal, inject } from '@angular/core';
import { Router } from '@angular/router';
import { form, Field, required } from '@angular/forms/signals';
import { ButtonComponent } from '../../shared/components/button/button.component';
import { GithubService } from '../../core/services/api/github.service';
import { WorkflowStateService } from '../../core/services/state/workflow-state.service';
import { CommonModule } from '@angular/common';

interface RepoFormModel {
  repoUrl: string;
}

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, Field, ButtonComponent],
  templateUrl: './home.component.html',
})
export class HomeComponent {
  private readonly githubService = inject(GithubService);
  private readonly workflowState = inject(WorkflowStateService);
  private readonly router = inject(Router);

  isLoading = signal(false);
  errorMessage = signal<string | null>(null);

  repoFormModel = signal<RepoFormModel>({
    repoUrl: '',
  });

  repoForm = form(this.repoFormModel, (schemaPath) => {
    required(schemaPath.repoUrl, { message: 'GitHub repository URL is required' });
  });

  isFormValid = computed(() => {
    const url = this.repoFormModel().repoUrl.trim();
    return (
      url.length > 0 &&
      this.isValidGitHubUrl(url) &&
      !this.repoForm().invalid()
    );
  });

  private isValidGitHubUrl(url: string): boolean {
    const githubRegex = /^https?:\/\/(www\.)?github\.com\/[\w-]+\/[\w.-]+\/?$/;
    return githubRegex.test(url);
  }

  handleSubmit(): void {
    if (!this.isFormValid()) {
      this.errorMessage.set('Please enter a valid GitHub repository URL');
      return;
    }

    this.errorMessage.set(null);
    this.isLoading.set(true);

    const repoUrl = this.repoFormModel().repoUrl.trim();

    this.githubService.cloneRepository(repoUrl).subscribe({
      next: (response) => {
        if (response.status === 'success') {
          // Extract component names and details
          const components = response.componentsByType?.components || [];
          const componentNames = components.map(c => c.name);

          // Map to AnalysisComponent format
          const discoveredComponents = components.map(c => ({
            name: c.name,
            tsPath: c.typescriptPath,
            htmlPath: c.templatePath || '',
            stylesPath: c.stylesPath || '',
            relativePath: c.relativePath
          }));

          this.workflowState.setRepoInfo({
            repoUrl: repoUrl,
            localPath: response.localPath,
            repoId: response.repoName,
            availableComponents: componentNames,
            discoveredComponents: discoveredComponents
          });

          this.router.navigate(['/configure']);
        } else {
          this.errorMessage.set(response.message || 'Failed to clone repository. Please try again.');
        }
        this.isLoading.set(false);
      },
      error: (error) => {
        console.error('Clone error:', error);
        this.errorMessage.set(
          error.message || 'An error occurred while cloning the repository'
        );
        this.isLoading.set(false);
      },
    });
  }
}
