import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../auth/auth.service';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="header">
      <a routerLink="/" class="brand">Stay Hotel</a>
      <nav class="nav" aria-label="Account">
        @if (auth.user(); as user) {
          <a routerLink="/my-bookings" routerLinkActive="active" class="nav-link">My bookings</a>
          <a routerLink="/profile" routerLinkActive="active" class="nav-link">{{ user.fullName }}</a>
          <button type="button" class="nav-button" (click)="logout()" [disabled]="loggingOut()">Sign out</button>
        } @else {
          <a routerLink="/login" routerLinkActive="active" class="nav-link">Sign in</a>
          <a routerLink="/register" routerLinkActive="active" class="nav-link">Register</a>
        }
      </nav>
    </header>
    <main class="content">
      <router-outlet />
    </main>
    <footer class="footer">© Stay Hotel</footer>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 100vh; }
    .header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 0 16px; min-height: 56px; background: var(--color-primary); }
    .brand { color: var(--color-on-primary); font-weight: 700; font-size: 1.25rem; text-decoration: none; }
    .nav { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
    .nav-link { color: var(--color-on-primary); text-decoration: none; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .nav-link.active, .nav-link:hover { text-decoration: underline; }
    .nav-button { font: inherit; color: var(--color-on-primary); background: transparent; border: 1px solid var(--color-on-primary); border-radius: var(--radius); padding: 4px 10px; cursor: pointer; }
    .content { flex: 1; width: 100%; max-width: 1200px; margin: 0 auto; padding: 24px 16px; }
    .footer { padding: 16px; text-align: center; color: var(--color-muted); font-size: 0.875rem; }
  `,
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly loggingOut = signal(false);

  logout(): void {
    this.loggingOut.set(true);
    this.auth.logout().pipe(finalize(() => this.loggingOut.set(false))).subscribe();
  }
}
