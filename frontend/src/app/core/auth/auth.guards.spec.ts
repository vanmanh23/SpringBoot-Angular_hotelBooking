import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, RouterStateSnapshot, UrlTree } from '@angular/router';
import { authGuard, roleGuard, safeReturnUrl } from './auth.guards';

describe('auth guards', () => {
  const route = {} as ActivatedRouteSnapshot;
  const state = { url: '/profile' } as RouterStateSnapshot;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('authGuard redirects anonymous users to login with a return URL', () => {
    const result = TestBed.runInInjectionContext(() => authGuard(route, state)) as UrlTree;
    expect(result.toString()).toBe('/login?returnUrl=%2Fprofile');
  });

  it('roleGuard redirects anonymous users to login', () => {
    const result = TestBed.runInInjectionContext(() => roleGuard('ADMIN')(route, state)) as UrlTree;
    expect(result.toString()).toContain('/login');
  });

  it.each([
    ['/profile', '/profile'],
    ['/hotels?city=Hue', '/hotels?city=Hue'],
    ['https://evil.example', '/'],
    ['//evil.example', '/'],
    ['/\\evil.example', '/'],
    [undefined, '/'],
  ])('safeReturnUrl(%s) -> %s', (input, expected) => {
    expect(safeReturnUrl(input)).toBe(expected);
  });
});
