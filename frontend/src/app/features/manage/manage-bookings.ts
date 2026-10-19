import { ChangeDetectionStrategy, Component, inject, input, OnInit, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { BookingStatus, STATUS_LABELS } from '../../core/bookings/booking.models';
import { toApiError } from '../../core/http/api-error';
import { LoadError } from '../../shared/ui/load-error';
import { ManageApi, ManagedBooking } from '../../core/manage/manage-api';
import { VndPipe } from '../../shared/pipes/vnd.pipe';

/** Arrivals / bookings of one hotel, ordered by check-in (UC-11). Read-only. */
@Component({
  selector: 'app-manage-bookings',
  imports: [VndPipe, LoadError],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card">
      <h2>Bookings</h2>
      <div class="toolbar">
        <div class="field"><label for="mb-status">Status</label>
          <select id="mb-status" #status>
            <option value="">All</option>
            @for (s of statuses; track s) { <option [value]="s">{{ labels[s] }}</option> }
          </select></div>
        <div class="field"><label for="mb-from">Staying from</label><input id="mb-from" type="date" #from /></div>
        <div class="field"><label for="mb-to">Until</label><input id="mb-to" type="date" #to /></div>
        <button class="btn btn-secondary" type="button" (click)="apply(status.value, from.value, to.value)">Filter</button>
      </div>
      <app-load-error [message]="error()" (retry)="load(page())" />
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>Code</th><th>Guest</th><th>Stay</th><th>Guests</th><th>Status</th><th>Total</th></tr></thead>
          <tbody>
            @for (b of bookings(); track b.id) {
              <tr>
                <td>{{ b.bookingCode }}</td>
                <td>{{ b.contactName }}</td>
                <td>{{ b.checkInDate }} → {{ b.checkOutDate }} ({{ b.nights }}n)</td>
                <td>{{ b.adults }}{{ b.children ? ' + ' + b.children : '' }}</td>
                <td><span class="status-pill">{{ labels[b.status] }}</span>{{ b.refundStatus !== 'NONE' ? ' · refund ' + b.refundStatus : '' }}</td>
                <td>{{ b.totalAmount | vnd }}</td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="muted">{{ loading() ? 'Loading…' : 'No bookings match.' }}</td></tr>
            }
          </tbody>
        </table>
      </div>
      @if (totalPages() > 1) {
        <nav class="pager" aria-label="Pages">
          <button class="btn btn-secondary btn-small" type="button" [disabled]="page() === 0" (click)="load(page() - 1)">Previous</button>
          <span>Page {{ page() + 1 }} / {{ totalPages() }}</span>
          <button class="btn btn-secondary btn-small" type="button" [disabled]="page() + 1 >= totalPages()" (click)="load(page() + 1)">Next</button>
        </nav>
      }
    </section>
  `,
  styles: `
    h2 { margin-top: 0; font-size: 1.1rem; }
    .pager { display: flex; gap: 12px; align-items: center; margin-top: 12px; }
  `,
})
export class ManageBookings implements OnInit {
  private readonly api = inject(ManageApi);

  readonly hotelId = input.required<string>();

  protected readonly statuses: BookingStatus[] = ['PENDING_PAYMENT', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'EXPIRED'];
  protected readonly labels = STATUS_LABELS;
  protected readonly bookings = signal<ManagedBooking[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  private filter: { status: BookingStatus | null; from: string | null; to: string | null } = { status: null, from: null, to: null };

  ngOnInit(): void {
    this.load(0);
  }

  apply(status: string, from: string, to: string): void {
    this.filter = { status: (status || null) as BookingStatus | null, from: from || null, to: to || null };
    this.load(0);
  }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .bookings(this.hotelId(), this.filter, page)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (p) => {
          this.bookings.set(p.content);
          this.page.set(p.page);
          this.totalPages.set(p.totalPages);
        },
        error: (err) => this.error.set(toApiError(err).message),
      });
  }
}
