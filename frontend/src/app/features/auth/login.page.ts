import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { safeReturnUrl } from '../../core/auth/auth.guards';
import { AuthService } from '../../core/auth/auth.service';
import { toApiError } from '../../core/http/api-error';
import { FieldError } from '../../shared/forms/field-error';

@Component({
  selector: 'app-login-page',
  imports: [ReactiveFormsModule, RouterLink, FieldError],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card form-narrow">
      <h1>Sign in</h1>
      @if (reason() === 'expired') {
        <p class="alert alert-error" role="status">Your session has ended. Please sign in again.</p>
      }
      @if (reason() === 'password-changed') {
        <p class="alert alert-success" role="status">Password changed. Please sign in with your new password.</p>
      }
      @if (error()) {
        <p class="alert alert-error" role="alert">{{ error() }}</p>
      }
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label for="email">Email</label>
          <input id="email" type="email" formControlName="email" autocomplete="username" aria-describedby="email-error" />
          <app-field-error [control]="form.controls.email" id="email-error" />
        </div>
        <div class="field">
          <label for="password">Password</label>
          <input id="password" type="password" formControlName="password" autocomplete="current-password" aria-describedby="password-error" />
          <app-field-error [control]="form.controls.password" id="password-error" />
        </div>
        <button class="btn btn-primary btn-block" type="submit" [disabled]="submitting()">
          {{ submitting() ? 'Signing in…' : 'Sign in' }}
        </button>
      </form>
      <p class="muted">No account? <a routerLink="/register" [queryParams]="{ returnUrl: returnUrl() }">Create one</a></p>
    </section>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Bound from query params (withComponentInputBinding). */
  readonly returnUrl = input<string | undefined>();
  readonly reason = input<string | undefined>();

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    password: ['', [Validators.required, Validators.maxLength(72)]],
  });

  submit(): void {
    if (this.submitting()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth
      .login(this.form.getRawValue())
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => void this.router.navigateByUrl(safeReturnUrl(this.returnUrl())),
        error: (err) => {
          const apiError = toApiError(err);
          this.error.set(
            apiError.code === 'INVALID_CREDENTIALS'
              ? 'Incorrect email or password.'
              : apiError.code === 'ACCOUNT_LOCKED'
                ? 'This account is locked. Please contact support.'
                : apiError.message,
          );
        },
      });
  }
}
