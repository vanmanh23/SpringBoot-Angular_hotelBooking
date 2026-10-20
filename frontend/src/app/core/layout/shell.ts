import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { UnreadNotifications } from '../notifications/notification-api';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="header">
      <div class="header-inner">
        <a routerLink="/" class="brand" aria-label="Stay Hotel home">
          <svg class="logo" viewBox="0 0 32 32" width="30" height="30" aria-hidden="true" focusable="false">
            <path d="M16 3c-1.6 0-2.8.9-3.6 2.6L4.6 22.4C3.7 24.4 4.9 27 7.3 27c1.5 0 2.7-.8 4-2.2L16 19.8l4.7 5c1.3 1.4 2.5 2.2 4 2.2 2.4 0 3.6-2.6 2.7-4.6L19.6 5.6C18.8 3.9 17.6 3 16 3zm0 6.2 4.9 10.6c-1.5-.2-3-1.2-4.9-3.3-1.9 2.1-3.4 3.1-4.9 3.3L16 9.2z" fill="currentColor"/>
          </svg>
          <span class="brand-name">Stay Hotel</span>
        </a>
        <nav class="nav" aria-label="Account">
          @if (auth.user(); as user) {
            <a routerLink="/my-bookings" routerLinkActive="active" class="nav-link">My bookings</a>
            @if (auth.hasRole('HOTEL_MANAGER') || auth.hasRole('ADMIN')) {
              <a routerLink="/manage" routerLinkActive="active" class="nav-link">Manage</a>
            }
            @if (auth.hasRole('ADMIN')) {
              <a routerLink="/admin" routerLinkActive="active" class="nav-link">Admin</a>
            }
            <a routerLink="/notifications" routerLinkActive="active" class="nav-link icon-link bell"
               [attr.aria-label]="unread.count() ? 'Notifications, ' + unread.count() + ' unread' : 'Notifications'">
              <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
                <path d="M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22zm7-6V11a7 7 0 0 0-5.5-6.84V3.5a1.5 1.5 0 0 0-3 0v.66A7 7 0 0 0 5 11v5l-2 2v1h18v-1l-2-2z" fill="currentColor"/>
              </svg>
              @if (unread.count() > 0) { <span class="badge" aria-hidden="true">{{ unread.count() > 99 ? '99+' : unread.count() }}</span> }
            </a>
            <a routerLink="/profile" routerLinkActive="active" class="account">
              <span class="avatar" aria-hidden="true">{{ initial(user.fullName) }}</span>
              <span class="account-name">{{ user.fullName }}</span>
            </a>
            <button type="button" class="nav-button" (click)="logout()" [disabled]="loggingOut()">Sign out</button>
          } @else {
            <a routerLink="/login" routerLinkActive="active" class="nav-link">Sign in</a>
            <a routerLink="/register" class="btn btn-primary btn-small register">Register</a>
          }
        </nav>
      </div>
    </header>
    <main class="content">
      <router-outlet />
    </main>
    <footer class="footer">
      <div class="footer-inner">
        <span>© Stay Hotel</span>
        <span class="muted">Stays across Vietnam · Prices in VND</span>
      </div>
    </footer>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 100vh; }
    .header { position: sticky; top: 0; z-index: 10; background: rgba(255, 255, 255, 0.96); border-bottom: 1px solid var(--color-border);
      backdrop-filter: saturate(180%) blur(8px); }
    .header-inner { max-width: 1280px; margin: 0 auto; padding: 0 24px; min-height: 76px; display: flex; align-items: center;
      justify-content: space-between; gap: 16px; }
    .brand { display: inline-flex; align-items: center; gap: 8px; color: var(--color-primary); text-decoration: none; }
    .brand:hover { color: var(--color-primary-hover); }
    .brand-name { font-weight: 800; font-size: 1.3rem; letter-spacing: -0.02em; }
    .nav { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; justify-content: flex-end; }
    .nav-link { color: var(--color-text); text-decoration: none; font-weight: 600; padding: 10px 14px; border-radius: var(--radius-pill);
      transition: background 0.15s; }
    .nav-link:hover { background: var(--color-surface-alt); color: var(--color-text); }
    .nav-link.active { background: var(--color-primary-soft); color: var(--color-primary-hover); }
    .icon-link { display: inline-flex; align-items: center; position: relative; padding: 10px; }
    .badge { position: absolute; top: 2px; right: 0; min-width: 18px; padding: 0 5px; border-radius: 9px; background: var(--color-primary);
      color: #fff; font-size: 0.7rem; font-weight: 700; line-height: 18px; text-align: center; border: 2px solid #fff; }
    .account { display: inline-flex; align-items: center; gap: 8px; padding: 5px 12px 5px 5px; margin-left: 4px; border: 1px solid var(--color-border);
      border-radius: var(--radius-pill); color: var(--color-text); text-decoration: none; font-weight: 600; transition: box-shadow 0.15s; }
    .account:hover, .account.active { box-shadow: var(--shadow); color: var(--color-text); }
    .avatar { width: 30px; height: 30px; border-radius: 50%; display: grid; place-items: center; background: #222; color: #fff; font-size: 0.85rem; }
    .account-name { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .nav-button { font: inherit; font-weight: 600; color: var(--color-text); background: transparent; border: 0; border-radius: var(--radius-pill);
      padding: 10px 14px; cursor: pointer; text-decoration: underline; text-underline-offset: 2px; }
    .nav-button:hover { background: var(--color-surface-alt); }
    .register { margin-left: 4px; border-radius: var(--radius-pill); }
    .content { flex: 1; width: 100%; max-width: 1280px; margin: 0 auto; padding: 32px 24px 48px; }
    .footer { border-top: 1px solid var(--color-border); background: var(--color-surface-alt); }
    .footer-inner { max-width: 1280px; margin: 0 auto; padding: 24px; display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap;
      font-size: 0.9rem; }
    @media (max-width: 720px) {
      .header-inner { padding: 8px 16px; min-height: 64px; }
      .brand-name { display: none; }
      .account-name { max-width: 90px; }
      .nav-link { padding: 8px 10px; }
      .content { padding: 20px 16px 32px; }
    }
  `,
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly unread = inject(UnreadNotifications);
  protected readonly loggingOut = signal(false);

  protected initial(name: string): string {
    return (name.trim()[0] ?? '?').toUpperCase();
  }

  logout(): void {
    this.loggingOut.set(true);
    this.auth.logout().pipe(finalize(() => this.loggingOut.set(false))).subscribe();
  }
}
