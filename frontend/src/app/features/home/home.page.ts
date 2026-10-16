import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'app-home-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="hero">
      <h1>Find your next stay</h1>
      <p>Hotel search is coming in the next milestone.</p>
    </section>
  `,
  styles: `
    .hero { padding: 48px 0; }
    h1 { margin: 0 0 8px; font-size: 2rem; }
    p { margin: 0; color: var(--color-muted); }
  `,
})
export class HomePage {}
