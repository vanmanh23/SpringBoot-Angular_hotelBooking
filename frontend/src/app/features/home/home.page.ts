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
      <p class="muted">Search hotels by destination, dates and number of guests.</p>
      <app-search-form (searched)="search($event)" />
      <p class="popular">
        Popular:
        @for (city of popular; track city) {
          <a [routerLink]="['/hotels']" [queryParams]="{ city: city }">{{ city }}</a>
        }
      </p>
    </section>
  `,
  styles: `
    .hero { padding: 32px 0; }
    h1 { margin: 0 0 8px; font-size: 2rem; }
    .hero > p { margin: 0 0 16px; }
    .popular { margin-top: 16px; display: flex; gap: 12px; flex-wrap: wrap; }
  `,
})
export class HomePage {
  private readonly router = inject(Router);
  protected readonly popular = ['Đà Nẵng', 'Hà Nội', 'Hồ Chí Minh', 'Nha Trang', 'Phú Quốc'];

  search(query: HotelSearchQuery): void {
    void this.router.navigate(['/hotels'], { queryParams: toQueryParams(query) });
  }
}
