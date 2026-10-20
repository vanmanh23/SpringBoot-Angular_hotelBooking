import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { HotelSearchQuery } from '../../core/hotels/hotel.models';
import { toQueryParams } from '../../core/hotels/search-query';
import { SearchForm } from '../hotels/search-form';

@Component({
  selector: 'app-home-page',
  imports: [SearchForm, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="hero">
      <h1>Find your next stay</h1>
      <p class="lead">Search hotels by destination, dates and number of guests.</p>
      <app-search-form (searched)="search($event)" />
    </section>
    <section class="popular" aria-labelledby="popular-title">
      <h2 id="popular-title">Popular destinations</h2>
      <ul class="chips">
        @for (city of popular; track city) {
          <li><a class="chip" [routerLink]="['/hotels']" [queryParams]="{ city: city }">{{ city }}</a></li>
        }
      </ul>
    </section>
    <section class="perks" aria-label="Why book with Stay Hotel">
      <div class="perk"><strong>Prices you can trust</strong><span class="muted">VAT and service fee shown before you pay.</span></div>
      <div class="perk"><strong>Free cancellation</strong><span class="muted">Up to 48 hours before check-in, refunded automatically.</span></div>
      <div class="perk"><strong>Verified reviews</strong><span class="muted">Only guests who completed a stay can review.</span></div>
    </section>
  `,
  styles: `
    .hero { padding: 56px 32px 40px; border-radius: 24px; text-align: center;
      background: radial-gradient(1200px 300px at 50% -40%, #ffe3ec 0%, #fff5f8 45%, #ffffff 100%); }
    h1 { margin: 0 0 8px; font-size: clamp(1.9rem, 4vw, 2.75rem); font-weight: 800; letter-spacing: -0.03em; }
    .lead { margin: 0 auto 28px; max-width: 520px; color: var(--color-muted); font-size: 1.05rem; }
    .popular { margin-top: 40px; }
    .popular h2 { margin: 0 0 16px; }
    .chips { list-style: none; padding: 0; margin: 0; display: flex; gap: 10px; flex-wrap: wrap; }
    .chip { display: inline-block; padding: 10px 18px; border: 1px solid var(--color-border); border-radius: var(--radius-pill);
      text-decoration: none; font-weight: 600; color: var(--color-text); transition: box-shadow 0.15s, border-color 0.15s; }
    .chip:hover { border-color: var(--color-text); box-shadow: var(--shadow-sm); color: var(--color-text); }
    .perks { margin-top: 48px; display: grid; gap: 24px; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); }
    .perk { display: flex; flex-direction: column; gap: 4px; padding-top: 16px; border-top: 1px solid var(--color-border); }
    @media (max-width: 600px) { .hero { padding: 32px 16px 24px; border-radius: 16px; } }
  `,
})
export class HomePage {
  private readonly router = inject(Router);
  protected readonly popular = ['Đà Nẵng', 'Hà Nội', 'Hồ Chí Minh', 'Nha Trang', 'Phú Quốc'];

  search(query: HotelSearchQuery): void {
    void this.router.navigate(['/hotels'], { queryParams: toQueryParams(query) });
  }
}
