import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { API_BASE_URL } from '../config/api.config';
import { AuthService } from './auth.service';

/**
 * Adds the bearer token to API calls. On a 401 caused by an expired access token, refreshes once (shared across
 * concurrent requests) and replays the request. Replaying is safe: a 401 is produced by the authentication filter
 * before any business logic runs, so the original request had no side effect.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const base = inject(API_BASE_URL);

  if (!req.url.startsWith(base)) {
    return next(req);
  }
  const isAuthEndpoint = req.url.startsWith(`${base}/auth/`);
  const authorize = (request: HttpRequest<unknown>, token: string | null) =>
    token && !isAuthEndpoint ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;

  const sentWithToken = auth.accessToken();
  return next(authorize(req, sentWithToken)).pipe(
    catchError((err) => {
      if (isAuthEndpoint || !sentWithToken || !(err instanceof HttpErrorResponse) || err.status !== 401) {
        return throwError(() => err);
      }
      return auth.refreshAccessToken().pipe(
        catchError(() => {
          auth.expireSession();
          return throwError(() => err);
        }),
        switchMap((token) => next(authorize(req, token))),
      );
    }),
  );
};
