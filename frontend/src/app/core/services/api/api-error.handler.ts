import { Observable, throwError } from 'rxjs';
import { ApiError } from '../../models/api.models';

export interface ErrorMessageOverrides {
  badRequest?: string;
  notFound?: string;
  serverError?: string;
  connectionError?: string;
  defaultError?: string;
}

export function handleApiError(
  error: any,
  overrides: ErrorMessageOverrides = {}
): Observable<never> {
  let errorMessage: string;
  let statusCode: number;

  if (error.error instanceof ErrorEvent) {
    errorMessage = `Error: ${error.error.message}`;
    statusCode = 0;
  } else {
    statusCode = error.status || 500;

    switch (statusCode) {
      case 400:
        errorMessage =
          error.error?.message ||
          overrides.badRequest ||
          'Invalid request.';
        break;
      case 404:
        errorMessage =
          overrides.notFound || 'Resource not found.';
        break;
      case 500:
        errorMessage =
          error.error?.message ||
          overrides.serverError ||
          'Server error. Please try again later.';
        break;
      case 0:
        errorMessage =
          overrides.connectionError ||
          'Unable to connect to server. Please check your connection.';
        break;
      default:
        errorMessage =
          error.error?.message ||
          overrides.defaultError ||
          'An unexpected error occurred.';
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
