import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  catchError,
  finalize,
  map,
  Observable,
  of,
  shareReplay,
  switchMap,
  tap,
  throwError,
  timer,
} from 'rxjs';
import { hasErrorCode } from '../http/api-error';
import { AuthApi } from './auth-api';
import { AuthResponse, LoginRequest, Role, User } from './auth.models';

/**
 * Session state. The access token lives only in memory (never in localStorage); after a reload the session
 * is restored through the HttpOnly refresh cookie (DECISIONS P-07).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(AuthApi);
  private readonly router = inject(Router);

  private readonly token = signal<string | null>(null);
  private readonly currentUser = signal<User | null>(null);
  private refreshInFlight$: Observable<string> | null = null;

  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this.currentUser() !== null);

  accessToken(): string | null {
    return this.token();
  }

  hasRole(role: Role): boolean {
    return this.currentUser()?.roles.includes(role) ?? false;
  }

  login(request: LoginRequest): Observable<User> {
    return this.api.login(request).pipe(map((res) => this.setSession(res)));
  }

  /** Called once at start-up; a missing/expired cookie simply means "anonymous". */
  restoreSession(): Observable<void> {
    return this.refreshAccessToken().pipe(
      map(() => undefined),
      catchError(() => of(undefined)),
    );
  }

  /**
   * Single-flight refresh: concurrent callers share one request. REFRESH_TOKEN_STALE means another tab just
   * rotated the cookie; the browser now holds the new one, so retry once.
   */
  refreshAccessToken(): Observable<string> {
    if (!this.refreshInFlight$) {
      this.refreshInFlight$ = this.api.refresh().pipe(
        catchError((err) =>
          hasErrorCode(err, 'REFRESH_TOKEN_STALE')
            ? timer(300).pipe(switchMap(() => this.api.refresh()))
            : throwError(() => err),
        ),
        map((res) => {
          this.setSession(res);
          return res.accessToken;
        }),
        catchError((err) => {
          this.clearSession();
          return throwError(() => err);
        }),
        finalize(() => (this.refreshInFlight$ = null)),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    }
    return this.refreshInFlight$;
  }

  logout(): Observable<void> {
    return this.api.logout().pipe(
      catchError(() => of(undefined)),
      tap(() => {
        this.clearSession();
        void this.router.navigateByUrl('/');
      }),
      map(() => undefined),
    );
  }

  updateUser(user: User): void {
    this.currentUser.set(user);
  }

  /** Session is gone server-side (refresh failed or password changed): forget it and ask to sign in. */
  expireSession(returnUrl = this.router.url): void {
    this.clearSession();
    void this.router.navigate(['/login'], { queryParams: { returnUrl, reason: 'expired' } });
  }

  clearSession(): void {
    this.token.set(null);
    this.currentUser.set(null);
  }

  private setSession(res: AuthResponse): User {
    this.token.set(res.accessToken);
    this.currentUser.set(res.user);
    return res.user;
  }
}
