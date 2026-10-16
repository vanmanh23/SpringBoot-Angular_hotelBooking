import { HttpErrorResponse } from '@angular/common/http';

/** Mirrors the backend ApiError body (docs/API_CONTRACT.md). */
export interface ApiFieldError {
  field: string;
  message: string;
}

export interface ApiError {
  status: number;
  code: string;
  message: string;
  fieldErrors: ApiFieldError[];
}

export const NETWORK_ERROR = 'NETWORK_ERROR';

/** Normalizes any HTTP failure into an ApiError so components never parse raw responses. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return {
        status: 0,
        code: NETWORK_ERROR,
        message: 'Cannot reach the server. Check your connection and try again.',
        fieldErrors: [],
      };
    }
    const body = error.error as Partial<ApiError> | null;
    if (body && typeof body === 'object' && typeof body.code === 'string') {
      return {
        status: error.status,
        code: body.code,
        message: body.message ?? 'Request failed',
        fieldErrors: Array.isArray(body.fieldErrors) ? body.fieldErrors : [],
      };
    }
    return { status: error.status, code: 'HTTP_' + error.status, message: 'Something went wrong. Please try again.', fieldErrors: [] };
  }
  return { status: -1, code: 'UNKNOWN', message: 'Something went wrong. Please try again.', fieldErrors: [] };
}

export function hasErrorCode(error: unknown, code: string): boolean {
  return error instanceof HttpErrorResponse && (error.error as Partial<ApiError> | null)?.code === code;
}
