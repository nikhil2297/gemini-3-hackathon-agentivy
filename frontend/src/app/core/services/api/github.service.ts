import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { CloneRequest, CloneResponse, ApiError } from '../../models/api.models';

/**
 * GitHub repository operations service
 * Handles repository cloning and component discovery
 */
@Injectable({
  providedIn: 'root',
})
export class GithubService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = 'http://localhost:8080/api/v1';

  /**
   * Clone a GitHub repository
   * @param repoUrl Full GitHub repository URL (e.g., https://github.com/user/repo)
   * @returns Observable with clone response containing local path and repo ID
   */
  cloneRepository(repoUrl: string): Observable<CloneResponse> {
    // Validate GitHub URL format
    if (!this.isValidGithubUrl(repoUrl)) {
      return throwError(() => ({
        message: 'Invalid GitHub URL. Please provide a valid GitHub repository URL.',
        statusCode: 400,
      } as ApiError));
    }

    const request: CloneRequest = { repoUrl };

    return this.http
      .post<CloneResponse>(`${this.apiBaseUrl}/test/clone`, request)
      .pipe(
        map((response) => {
          // Validate response
          if (response.status !== 'success' || !response.localPath) {
            throw {
              message: response.message || 'Repository cloning failed',
              statusCode: 500,
            } as ApiError;
          }
          return response;
        }),
        catchError(this.handleError)
      );
  }

  /**
   * Validate GitHub URL format
   * @param url URL to validate
   * @returns True if valid GitHub URL
   */
  private isValidGithubUrl(url: string): boolean {
    const githubUrlPattern =
      /^https:\/\/github\.com\/[\w-]+\/[\w.-]+\/?$/;
    return githubUrlPattern.test(url);
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
            'Invalid request. Please check the repository URL.';
          break;
        case 404:
          errorMessage =
            'Repository not found. Please check the URL and ensure it is public.';
          break;
        case 500:
          errorMessage =
            error.error?.message ||
            'Server error. Please try again later.';
          break;
        case 0:
          errorMessage =
            'Unable to connect to server. Please check your connection.';
          break;
        default:
          errorMessage =
            error.error?.message || 'An unexpected error occurred.';
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
