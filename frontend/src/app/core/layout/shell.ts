import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="header">
      <a routerLink="/" class="brand">Stay Hotel</a>
    </header>
    <main class="content">
      <router-outlet />
    </main>
    <footer class="footer">© Stay Hotel</footer>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 100vh; }
    .header { display: flex; align-items: center; padding: 0 16px; height: 56px; background: var(--color-primary); }
    .brand { color: var(--color-on-primary); font-weight: 700; font-size: 1.25rem; text-decoration: none; }
    .content { flex: 1; width: 100%; max-width: 1200px; margin: 0 auto; padding: 24px 16px; box-sizing: border-box; }
    .footer { padding: 16px; text-align: center; color: var(--color-muted); font-size: 0.875rem; }
  `,
})
export class Shell {}
