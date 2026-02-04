import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  AnalysisConfig,
  AutoFixConfig,
  ReportResponse,
  FixResult,
  ApiError,
} from '../../models/api.models';

/**
 * Component analysis and testing service
 * Handles report generation and auto-fix operations
 */
@Injectable({
  providedIn: 'root',
})
export class AnalysisService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = 'http://localhost:8080/api';

  /**
   * Generate analysis report only (no fixes)
   * POST /api/v1/agent/repo
   * @param config Analysis configuration
   * @returns Observable with analysis report
   */
  generateReport(config: AnalysisConfig): Observable<ReportResponse> {
    const payload = {
      repoPath: config.repoPath,
      repoId: config.repoId,
      component: config.component,
      tests: config.tests,
    };

    return this.http
      .post<ReportResponse>(`${this.apiBaseUrl}/v1/agent/repo`, payload)
      .pipe(catchError(this.handleError));
  }

  /**
   * Test and automatically fix issues
   * POST /api/workflow/test-and-fix
   * @param config Auto-fix configuration
   * @returns Observable with fix results
   */
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
      .pipe(catchError(this.handleError));
  }

  /**
   * Test a specific component
   * POST /api/workflow/test-component
   * @param config Analysis configuration with specific component
   * @returns Observable with test results
   */
  testComponent(config: AnalysisConfig & { port?: number }): Observable<any> {
    const payload = {
      repoPath: config.repoPath,
      componentClassName: config.componentClassName,
      tests: config.tests,
      port: config.port || 4200,
    };

    return this.http
      .post(`${this.apiBaseUrl}/workflow/test-component`, payload)
      .pipe(catchError(this.handleError));
  }

  /**
   * Stop the development server
   * POST /api/workflow/stop-server
   */
  stopServer(): Observable<any> {
    return this.http
      .post(`${this.apiBaseUrl}/workflow/stop-server`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Handle HTTP errors
   * @param error HTTP error response
   * @returns Observable error with user-friendly message
   */
  private handleError(error: any): Observable<never> {
    let errorMessage: string;
    let statusCode: number;

    if (error.error instanceof ErrorEvent) {
      // Client-side error
      errorMessage = `Error: ${error.error.message}`;
      statusCode = 0;
    } else {
      // Server-side error
      statusCode = error.status || 500;

      switch (statusCode) {
        case 400:
          errorMessage =
            error.error?.message ||
            'Invalid configuration. Please check your settings.';
          break;
        case 404:
          errorMessage =
            'Component or repository not found. Please verify the component name.';
          break;
        case 500:
          errorMessage =
            error.error?.message ||
            'Analysis failed. Please try again later.';
          break;
        case 0:
          errorMessage =
            'Unable to connect to server. Please check your connection.';
          break;
        default:
          errorMessage =
            error.error?.message || 'An unexpected error occurred during analysis.';
      }
    }

    const apiError: ApiError = {
      message: errorMessage,
      statusCode,
      error: error.error?.error,
      details: error.error,
    };

    return throwError(() => apiError);
  }
}
