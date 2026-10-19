import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { User } from '../../core/auth/auth.models';
import { toApiError } from '../../core/http/api-error';
import { AdminApi, ManagedHotel } from '../../core/manage/manage-api';

/** Admin-only: assign / remove hotel managers. Candidates are users that hold the HOTEL_MANAGER role. */
@Component({
  selector: 'app-hotel-managers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card">
      <h2>Managers</h2>
      <ul class="list">
        @for (m of hotel().managerIds; track m) {
          <li><code>{{ m }}</code>
            <button class="btn btn-secondary btn-small" type="button" [disabled]="busy()" (click)="remove(m)">Remove</button></li>
        } @empty { <li class="muted">No manager assigned.</li> }
      </ul>
      <div class="toolbar">
        <div class="field"><label for="mgr-q">Find a user with the Hotel manager role</label>
          <input id="mgr-q" #q placeholder="name or email" (keydown.enter)="search(q.value)" /></div>
        <button class="btn btn-secondary" type="button" (click)="search(q.value)">Search</button>
      </div>
      @for (u of candidates(); track u.id) {
        <div class="candidate">
          <span>{{ u.fullName }} · {{ u.email }}</span>
          @if (hotel().managerIds.includes(u.id)) { <span class="muted">assigned</span> }
          @else { <button class="btn btn-primary btn-small" type="button" [disabled]="busy()" (click)="add(u)">Assign</button> }
        </div>
      }
      @if (searched() && !candidates().length) {
        <p class="muted">No hotel manager matches. Grant the role first in Admin → Users.</p>
      }
      @if (error()) { <p class="alert alert-error" role="alert">{{ error() }}</p> }
    </section>
  `,
  styles: `
    h2 { margin-top: 0; font-size: 1.1rem; }
    .list { list-style: none; padding: 0; display: flex; flex-direction: column; gap: 6px; }
    .list li, .candidate { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; padding: 4px 0; }
  `,
})
export class HotelManagers {
  private readonly admin = inject(AdminApi);

  readonly hotel = input.required<ManagedHotel>();
  readonly changed = output<void>();

  protected readonly candidates = signal<User[]>([]);
  protected readonly searched = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  search(q: string): void {
    this.admin.users(q.trim() || null, 'ACTIVE', 0, 20).subscribe({
      next: (p) => {
        this.searched.set(true);
        this.candidates.set(p.content.filter((u) => u.roles.includes('HOTEL_MANAGER')));
      },
      error: (err) => this.error.set(toApiError(err).message),
    });
  }

  add(user: User): void {
    this.busy.set(true);
    this.admin.addManager(this.hotel().id, user.id).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: () => this.changed.emit(),
      error: (err) => this.error.set(toApiError(err).message),
    });
  }

  remove(userId: string): void {
    this.busy.set(true);
    this.admin.removeManager(this.hotel().id, userId).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: () => this.changed.emit(),
      error: (err) => this.error.set(toApiError(err).message),
    });
  }
}
