import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  AnalysisConfig,
  AutoFixConfig,
  ReportResponse,
  FixResult,
} from '../../models/api.models';
import { API_BASE_URL } from '../../tokens/api.tokens';
import { handleApiError } from './api-error.handler';

@Injectable({
  providedIn: 'root',
})
export class AnalysisService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);

  generateReport(config: AnalysisConfig): Observable<ReportResponse> {
    const payload = {
      repoPath: config.repoPath,
      repoId: config.repoId,
      component: config.component,
      tests: config.tests,
    };

    return this.http
      .post<ReportResponse>(`${this.apiBaseUrl}/v1/agent/repo`, payload)
      .pipe(catchError(err => handleApiError(err, {
        badRequest: 'Invalid configuration. Please check your settings.',
        notFound: 'Component or repository not found. Please verify the component name.',
        serverError: 'Analysis failed. Please try again later.',
        defaultError: 'An unexpected error occurred during analysis.',
      })));
  }

  testAndFix(config: AutoFixConfig): Observable<FixResult> {
    const payload = {
      repoPath: config.repoPath,
      componentClassName: config.componentClassName,
      tests: config.tests,
      maxIterations: config.maxIterations,
      port: config.port,
    };

    return this.http
      .post<FixResult>(`${this.apiBaseUrl}/workflow/test-and-fix`, payload)
      .pipe(catchError(err => handleApiError(err, {
        badRequest: 'Invalid configuration. Please check your settings.',
        notFound: 'Component or repository not found. Please verify the component name.',
        serverError: 'Analysis failed. Please try again later.',
        defaultError: 'An unexpected error occurred during analysis.',
      })));
  }

  testComponent(config: AnalysisConfig & { port?: number }): Observable<any> {
    const payload = {
      repoPath: config.repoPath,
      componentClassName: config.componentClassName,
      tests: config.tests,
      port: config.port || 4200,
    };

    return this.http
      .post(`${this.apiBaseUrl}/workflow/test-component`, payload)
      .pipe(catchError(err => handleApiError(err, {
        badRequest: 'Invalid configuration. Please check your settings.',
        notFound: 'Component or repository not found. Please verify the component name.',
        serverError: 'Analysis failed. Please try again later.',
        defaultError: 'An unexpected error occurred during analysis.',
      })));
  }

  stopServer(): Observable<any> {
    return this.http
      .post(`${this.apiBaseUrl}/workflow/stop-server`, {})
      .pipe(catchError(err => handleApiError(err, {
        serverError: 'Failed to stop server. Please try again.',
      })));
  }
}
