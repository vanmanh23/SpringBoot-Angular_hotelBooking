import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { Role, User, UserStatus } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { toApiError } from '../../core/http/api-error';
import { AdminApi } from '../../core/manage/manage-api';

export const ROLE_LABELS: Record<Role, string> = { CUSTOMER: 'Customer', HOTEL_MANAGER: 'Hotel manager', ADMIN: 'Admin' };

@Component({
  selector: 'app-admin-users',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toolbar">
      <div class="field"><label for="au-q">Search</label><input id="au-q" #q placeholder="name or email" (keydown.enter)="apply(q.value, st.value)" /></div>
      <div class="field"><label for="au-status">Status</label>
        <select id="au-status" #st><option value="">All</option>
          @for (s of statuses; track s) { <option [value]="s">{{ s }}</option> }</select></div>
      <button class="btn btn-secondary" type="button" (click)="apply(q.value, st.value)">Search</button>
    </div>
    @if (error()) { <p class="alert alert-error" role="alert">{{ error() }}</p> }
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>User</th><th>Roles</th><th>Status</th><th>Joined</th><th></th></tr></thead>
        <tbody>
          @for (u of users(); track u.id) {
            <tr>
              <td>{{ u.fullName }}<br /><span class="muted">{{ u.email }}</span></td>
              <td>
                @for (r of allRoles; track r) {
                  <label class="role"><input type="checkbox" [checked]="u.roles.includes(r)" [disabled]="busy() === u.id || u.id === selfId"
                                             (change)="toggleRole(u, r)" /> {{ roleLabels[r] }}</label>
                }
              </td>
              <td><span class="status-pill">{{ u.status }}</span></td>
              <td>{{ u.createdAt | date: 'mediumDate' }}</td>
              <td>
                @if (u.id !== selfId) {
                  @if (u.status === 'ACTIVE') {
                    <button class="btn btn-secondary btn-small" type="button" [disabled]="busy() === u.id" (click)="setStatus(u, 'LOCKED')">Lock</button>
                  } @else {
                    <button class="btn btn-secondary btn-small" type="button" [disabled]="busy() === u.id" (click)="setStatus(u, 'ACTIVE')">Unlock</button>
                  }
                } @else { <span class="muted">you</span> }
              </td>
            </tr>
          } @empty { <tr><td colspan="5" class="muted">{{ loading() ? 'Loading…' : 'No users match.' }}</td></tr> }
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
  `,
  styles: `
    .role { display: block; white-space: nowrap; }
    .pager { display: flex; gap: 12px; align-items: center; margin-top: 12px; }
  `,
})
export class AdminUsers implements OnInit {
  private readonly api = inject(AdminApi);
  protected readonly selfId = inject(AuthService).user()?.id;
  protected readonly statuses: UserStatus[] = ['ACTIVE', 'LOCKED', 'DISABLED'];
  protected readonly allRoles: Role[] = ['CUSTOMER', 'HOTEL_MANAGER', 'ADMIN'];
  protected readonly roleLabels = ROLE_LABELS;

  protected readonly users = signal<User[]>([]);
  protected readonly loading = signal(false);
  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  private q: string | null = null;
  private status: UserStatus | null = null;

  ngOnInit(): void {
    this.load(0);
  }

  apply(q: string, status: string): void {
    this.q = q.trim() || null;
    this.status = (status || null) as UserStatus | null;
    this.load(0);
  }

  load(page: number): void {
    this.loading.set(true);
    this.api.users(this.q, this.status, page).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (p) => {
        this.users.set(p.content);
        this.page.set(p.page);
        this.totalPages.set(p.totalPages);
      },
      error: (err) => this.error.set(toApiError(err).message),
    });
  }

  setStatus(user: User, status: UserStatus): void {
    this.update(user, this.api.setUserStatus(user.id, status, status === 'LOCKED' ? 'Locked by administrator' : null));
  }

  toggleRole(user: User, role: Role): void {
    const roles = user.roles.includes(role) ? user.roles.filter((r) => r !== role) : [...user.roles, role];
    if (!roles.length) {
      this.error.set('A user needs at least one role.');
      this.users.update((list) => [...list]); // re-render to undo the checkbox
      return;
    }
    this.update(user, this.api.setUserRoles(user.id, roles));
  }

  private update(user: User, call: ReturnType<AdminApi['setUserRoles']>): void {
    this.busy.set(user.id);
    this.error.set(null);
    call.pipe(finalize(() => this.busy.set(null))).subscribe({
      next: (updated) => this.users.update((list) => list.map((u) => (u.id === updated.id ? updated : u))),
      error: (err) => {
        this.error.set(toApiError(err).message);
        this.users.update((list) => [...list]);
      },
    });
  }
}
