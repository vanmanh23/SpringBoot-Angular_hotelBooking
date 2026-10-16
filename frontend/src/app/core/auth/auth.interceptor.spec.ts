import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { authInterceptor } from './auth.interceptor';
import { AuthResponse } from './auth.models';
import { AuthService } from './auth.service';

const session = (token: string): AuthResponse => ({
  accessToken: token,
  tokenType: 'Bearer',
  expiresIn: 900,
  user: {
    id: 'u1',
    email: 'a@example.com',
    fullName: 'A',
    phone: null,
    status: 'ACTIVE',
    roles: ['CUSTOMER'],
    createdAt: '2026-01-01T00:00:00Z',
  },
});

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => backend.verify());

  async function signIn(token = 'old-token'): Promise<void> {
    const done = firstValueFrom(auth.login({ email: 'a@example.com', password: 'x' }));
    backend.expectOne('/api/v1/auth/login').flush(session(token));
    await done;
  }

  it('attaches the bearer token to API calls but not to auth endpoints or other hosts', async () => {
    await signIn();

    http.get('/api/v1/users/me').subscribe();
    expect(backend.expectOne('/api/v1/users/me').request.headers.get('Authorization')).toBe('Bearer old-token');

    http.post('/api/v1/auth/logout', null).subscribe();
    expect(backend.expectOne('/api/v1/auth/logout').request.headers.has('Authorization')).toBe(false);

    http.get('https://cdn.example.com/x').subscribe();
    expect(backend.expectOne('https://cdn.example.com/x').request.headers.has('Authorization')).toBe(false);
  });

  it('refreshes once on 401 and replays the request with the new token', async () => {
    await signIn();
    const result = firstValueFrom(http.get<{ ok: boolean }>('/api/v1/users/me'));

    backend.expectOne('/api/v1/users/me').flush({ code: 'UNAUTHORIZED' }, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/v1/auth/refresh').flush(session('new-token'));
    const replay = backend.expectOne('/api/v1/users/me');
    expect(replay.request.headers.get('Authorization')).toBe('Bearer new-token');
    replay.flush({ ok: true });

    expect(await result).toEqual({ ok: true });
    expect(auth.accessToken()).toBe('new-token');
  });

  it('shares a single refresh between concurrent 401 responses', async () => {
    await signIn();
    const a = firstValueFrom(http.get('/api/v1/a'));
    const b = firstValueFrom(http.get('/api/v1/b'));

    backend.expectOne('/api/v1/a').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/v1/b').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/v1/auth/refresh').flush(session('new-token'));
    backend.expectOne('/api/v1/a').flush('A');
    backend.expectOne('/api/v1/b').flush('B');

    expect(await a).toBe('A');
    expect(await b).toBe('B');
  });

  it('ends the session and redirects to login when refresh fails', async () => {
    await signIn();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const result = firstValueFrom(http.get('/api/v1/users/me'));

    backend.expectOne('/api/v1/users/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/v1/auth/refresh').flush(
      { code: 'REFRESH_TOKEN_REUSED', message: 'x' },
      { status: 401, statusText: 'Unauthorized' },
    );

    await expect(result).rejects.toMatchObject({ status: 401 });
    expect(auth.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], expect.objectContaining({ queryParams: expect.objectContaining({ reason: 'expired' }) }));
  });

  it('does not refresh for 403 or for anonymous requests', async () => {
    const anonymous = firstValueFrom(http.get('/api/v1/users/me'));
    backend.expectOne('/api/v1/users/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await expect(anonymous).rejects.toMatchObject({ status: 401 });

    await signIn();
    const forbidden = firstValueFrom(http.get('/api/v1/admin/users'));
    backend.expectOne('/api/v1/admin/users').flush(null, { status: 403, statusText: 'Forbidden' });
    await expect(forbidden).rejects.toMatchObject({ status: 403 });
    backend.expectNone('/api/v1/auth/refresh');
  });
});
