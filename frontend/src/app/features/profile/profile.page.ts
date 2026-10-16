import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthApi } from '../../core/auth/auth-api';
import { AuthService } from '../../core/auth/auth.service';
import { User } from '../../core/auth/auth.models';
import { toApiError } from '../../core/http/api-error';
import { FieldError } from '../../shared/forms/field-error';
import {
  applyServerFieldErrors,
  matchesControl,
  passwordStrength,
  PHONE_PATTERN,
} from '../../shared/forms/form-errors';

@Component({
  selector: 'app-profile-page',
  imports: [ReactiveFormsModule, FieldError],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>My profile</h1>
    @if (loading()) {
      <p class="muted" role="status">Loading profile…</p>
    } @else if (loadError()) {
      <div class="alert alert-error" role="alert">
        {{ loadError() }}
        <button class="btn btn-secondary" type="button" (click)="load()">Retry</button>
      </div>
    } @else if (user(); as u) {
      <div class="grid">
        <section class="card">
          <h2>Personal details</h2>
          <p class="muted">{{ u.email }} · {{ u.roles.join(', ') }}</p>
          @if (profileMessage(); as m) {
            <p class="alert" [class.alert-success]="m.ok" [class.alert-error]="!m.ok" role="status">{{ m.text }}</p>
          }
          <form [formGroup]="profileForm" (ngSubmit)="saveProfile()" novalidate>
            <div class="field">
              <label for="fullName">Full name</label>
              <input id="fullName" formControlName="fullName" autocomplete="name" />
              <app-field-error [control]="profileForm.controls.fullName" />
            </div>
            <div class="field">
              <label for="phone">Phone</label>
              <input id="phone" type="tel" formControlName="phone" autocomplete="tel" />
              <app-field-error [control]="profileForm.controls.phone" />
            </div>
            <button class="btn btn-primary" type="submit" [disabled]="savingProfile()">
              {{ savingProfile() ? 'Saving…' : 'Save changes' }}
            </button>
          </form>
        </section>

        <section class="card">
          <h2>Change password</h2>
          <p class="muted">You will be signed out on all devices.</p>
          @if (passwordError()) {
            <p class="alert alert-error" role="alert">{{ passwordError() }}</p>
          }
          <form [formGroup]="passwordForm" (ngSubmit)="changePassword()" novalidate>
            <div class="field">
              <label for="currentPassword">Current password</label>
              <input id="currentPassword" type="password" formControlName="currentPassword" autocomplete="current-password" />
              <app-field-error [control]="passwordForm.controls.currentPassword" />
            </div>
            <div class="field">
              <label for="newPassword">New password</label>
              <input id="newPassword" type="password" formControlName="newPassword" autocomplete="new-password" />
              <app-field-error [control]="passwordForm.controls.newPassword" />
            </div>
            <div class="field">
              <label for="confirmNewPassword">Confirm new password</label>
              <input id="confirmNewPassword" type="password" formControlName="confirmNewPassword" autocomplete="new-password" />
              <app-field-error [control]="passwordForm.controls.confirmNewPassword" />
            </div>
            <button class="btn btn-primary" type="submit" [disabled]="changingPassword()">
              {{ changingPassword() ? 'Updating…' : 'Change password' }}
            </button>
          </form>
        </section>
      </div>
    }
  `,
  styles: `
    .grid { display: grid; gap: 24px; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); }
    h2 { margin-top: 0; font-size: 1.25rem; }
  `,
})
export class ProfilePage implements OnInit {
  private readonly api = inject(AuthApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly user = signal<User | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly savingProfile = signal(false);
  protected readonly profileMessage = signal<{ ok: boolean; text: string } | null>(null);
  protected readonly changingPassword = signal(false);
  protected readonly passwordError = signal<string | null>(null);

  protected readonly profileForm = this.fb.group({
    fullName: ['', [Validators.required, Validators.maxLength(150)]],
    phone: ['', [Validators.pattern(PHONE_PATTERN)]],
  });

  protected readonly passwordForm = this.fb.group({
    currentPassword: ['', [Validators.required, Validators.maxLength(72)]],
    newPassword: ['', [Validators.required, passwordStrength]],
    confirmNewPassword: ['', [Validators.required, matchesControl('newPassword')]],
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .me()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (user) => this.showUser(user),
        error: (err) => this.loadError.set(toApiError(err).message),
      });
  }

  saveProfile(): void {
    if (this.savingProfile()) return;
    if (this.profileForm.invalid) {
      this.profileForm.markAllAsTouched();
      return;
    }
    const { fullName, phone } = this.profileForm.getRawValue();
    this.savingProfile.set(true);
    this.profileMessage.set(null);
    this.api
      .updateProfile({ fullName, phone: phone || null })
      .pipe(finalize(() => this.savingProfile.set(false)))
      .subscribe({
        next: (user) => {
          this.showUser(user);
          this.auth.updateUser(user);
          this.profileMessage.set({ ok: true, text: 'Profile updated.' });
        },
        error: (err) => {
          const apiError = toApiError(err);
          if (!applyServerFieldErrors(this.profileForm, apiError)) {
            this.profileMessage.set({ ok: false, text: apiError.message });
          }
        },
      });
  }

  changePassword(): void {
    if (this.changingPassword()) return;
    this.passwordForm.controls.confirmNewPassword.updateValueAndValidity();
    if (this.passwordForm.invalid) {
      this.passwordForm.markAllAsTouched();
      return;
    }
    const { currentPassword, newPassword } = this.passwordForm.getRawValue();
    this.changingPassword.set(true);
    this.passwordError.set(null);
    this.api
      .changePassword({ currentPassword, newPassword })
      .pipe(finalize(() => this.changingPassword.set(false)))
      .subscribe({
        next: () => {
          this.auth.clearSession();
          void this.router.navigate(['/login'], { queryParams: { reason: 'password-changed' } });
        },
        error: (err) => {
          const apiError = toApiError(err);
          if (apiError.code === 'CURRENT_PASSWORD_INCORRECT') {
            this.passwordForm.controls.currentPassword.setErrors({ server: 'Current password is incorrect.' });
            this.passwordForm.controls.currentPassword.markAsTouched();
          } else if (!applyServerFieldErrors(this.passwordForm, apiError)) {
            this.passwordError.set(apiError.message);
          }
        },
      });
  }

  private showUser(user: User): void {
    this.user.set(user);
    this.profileForm.reset({ fullName: user.fullName, phone: user.phone ?? '' });
  }
}
