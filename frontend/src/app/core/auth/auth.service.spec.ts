import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthResponse } from './auth.models';
import { AuthService } from './auth.service';

const session: AuthResponse = {
  accessToken: 'tok',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: {
    id: 'u1',
    email: 'a@example.com',
    fullName: 'A',
    phone: null,
    status: 'ACTIVE',
    roles: ['CUSTOMER', 'HOTEL_MANAGER'],
    createdAt: '2026-01-01T00:00:00Z',
  },
};

describe('AuthService', () => {
  let backend: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => backend.verify());

  it('restoreSession resolves anonymously when there is no valid cookie', async () => {
    const done = firstValueFrom(auth.restoreSession());
    backend.expectOne('/api/v1/auth/refresh').flush(
      { code: 'REFRESH_TOKEN_INVALID', message: 'x' },
      { status: 401, statusText: 'Unauthorized' },
    );
    await done;
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.accessToken()).toBeNull();
  });

  it('restoreSession signs the user in from the refresh cookie and exposes roles', async () => {
    const done = firstValueFrom(auth.restoreSession());
    backend.expectOne('/api/v1/auth/refresh').flush(session);
    await done;
    expect(auth.user()?.email).toBe('a@example.com');
    expect(auth.hasRole('HOTEL_MANAGER')).toBe(true);
    expect(auth.hasRole('ADMIN')).toBe(false);
  });

  it('retries once when another tab rotated the cookie (REFRESH_TOKEN_STALE)', async () => {
    const token = firstValueFrom(auth.refreshAccessToken());
    backend.expectOne('/api/v1/auth/refresh').flush(
      { code: 'REFRESH_TOKEN_STALE', message: 'x' },
      { status: 401, statusText: 'Unauthorized' },
    );
    await new Promise((resolve) => setTimeout(resolve, 350));
    backend.expectOne('/api/v1/auth/refresh').flush(session);

    expect(await token).toBe('tok');
  });

  it('never stores tokens in web storage', async () => {
    const done = firstValueFrom(auth.login({ email: 'a@example.com', password: 'x' }));
    backend.expectOne('/api/v1/auth/login').flush(session);
    await done;
    const stored = JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage });
    expect(stored).not.toContain('tok');
  });
});
