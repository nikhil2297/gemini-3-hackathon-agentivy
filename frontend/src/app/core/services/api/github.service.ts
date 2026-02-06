import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { CloneRequest, CloneResponse, ApiError } from '../../models/api.models';
import { API_BASE_URL } from '../../tokens/api.tokens';
import { handleApiError } from './api-error.handler';

@Injectable({
  providedIn: 'root',
})
export class GithubService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL) + '/v1';

  cloneRepository(repoUrl: string): Observable<CloneResponse> {
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
          if (response.status !== 'success' || !response.localPath) {
            throw {
              message: response.message || 'Repository cloning failed',
              statusCode: 500,
            } as ApiError;
          }
          return response;
        }),
        catchError(err => handleApiError(err, {
          badRequest: 'Invalid request. Please check the repository URL.',
          notFound: 'Repository not found. Please check the URL and ensure it is public.',
          serverError: 'Server error. Please try again later.',
        }))
      );
  }

  private isValidGithubUrl(url: string): boolean {
    const githubUrlPattern =
      /^https:\/\/github\.com\/[\w-]+\/[\w.-]+\/?$/;
    return githubUrlPattern.test(url);
  }
}
