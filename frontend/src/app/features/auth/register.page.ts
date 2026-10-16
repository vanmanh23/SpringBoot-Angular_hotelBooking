import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize, switchMap } from 'rxjs';
import { AuthApi } from '../../core/auth/auth-api';
import { safeReturnUrl } from '../../core/auth/auth.guards';
import { AuthService } from '../../core/auth/auth.service';
import { toApiError } from '../../core/http/api-error';
import { FieldError } from '../../shared/forms/field-error';
import {
  applyServerFieldErrors,
  matchesControl,
  passwordStrength,
  PHONE_PATTERN,
} from '../../shared/forms/form-errors';

@Component({
  selector: 'app-register-page',
  imports: [ReactiveFormsModule, RouterLink, FieldError],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card form-narrow">
      <h1>Create account</h1>
      @if (error()) {
        <p class="alert alert-error" role="alert">{{ error() }}</p>
      }
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label for="fullName">Full name</label>
          <input id="fullName" formControlName="fullName" autocomplete="name" />
          <app-field-error [control]="form.controls.fullName" />
        </div>
        <div class="field">
          <label for="email">Email</label>
          <input id="email" type="email" formControlName="email" autocomplete="email" />
          <app-field-error [control]="form.controls.email" />
        </div>
        <div class="field">
          <label for="phone">Phone (optional)</label>
          <input id="phone" type="tel" formControlName="phone" autocomplete="tel" />
          <app-field-error [control]="form.controls.phone" />
        </div>
        <div class="field">
          <label for="password">Password</label>
          <input id="password" type="password" formControlName="password" autocomplete="new-password" />
          <app-field-error [control]="form.controls.password" />
        </div>
        <div class="field">
          <label for="confirmPassword">Confirm password</label>
          <input id="confirmPassword" type="password" formControlName="confirmPassword" autocomplete="new-password" />
          <app-field-error [control]="form.controls.confirmPassword" />
        </div>
        <button class="btn btn-primary btn-block" type="submit" [disabled]="submitting()">
          {{ submitting() ? 'Creating account…' : 'Create account' }}
        </button>
      </form>
      <p class="muted">Already registered? <a routerLink="/login">Sign in</a></p>
    </section>
  `,
})
export class RegisterPage {
  private readonly api = inject(AuthApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly returnUrl = input<string | undefined>();

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    fullName: ['', [Validators.required, Validators.maxLength(150)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    phone: ['', [Validators.pattern(PHONE_PATTERN)]],
    password: ['', [Validators.required, passwordStrength]],
    confirmPassword: ['', [Validators.required, matchesControl('password')]],
  });

  submit(): void {
    if (this.submitting()) return;
    this.form.controls.confirmPassword.updateValueAndValidity();
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { fullName, email, phone, password } = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);
    this.api
      .register({ fullName, email, password, phone: phone || null })
      .pipe(
        switchMap(() => this.auth.login({ email, password })),
        finalize(() => this.submitting.set(false)),
      )
      .subscribe({
        next: () => void this.router.navigateByUrl(safeReturnUrl(this.returnUrl())),
        error: (err) => {
          const apiError = toApiError(err);
          if (apiError.code === 'EMAIL_ALREADY_REGISTERED') {
            this.form.controls.email.setErrors({ server: 'This email is already registered.' });
            this.form.controls.email.markAsTouched();
          } else if (!applyServerFieldErrors(this.form, apiError)) {
            this.error.set(apiError.message);
          }
        },
      });
  }
}
