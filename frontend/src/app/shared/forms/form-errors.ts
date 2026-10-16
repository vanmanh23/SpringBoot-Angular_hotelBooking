import { AbstractControl, FormGroup, ValidationErrors, ValidatorFn } from '@angular/forms';
import { ApiError } from '../../core/http/api-error';

/** Same rule as the backend (8-72 chars, at least one letter and one digit). */
export const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{8,72}$/;
export const PHONE_PATTERN = /^[0-9+() .-]{0,30}$/;

/** Human-readable message for the first error on a control, or null. */
export function controlErrorMessage(control: AbstractControl | null): string | null {
  const errors = control?.errors;
  if (!errors) return null;
  if (errors['server']) return errors['server'] as string;
  if (errors['required']) return 'This field is required.';
  if (errors['email']) return 'Enter a valid email address.';
  if (errors['maxlength']) return `Must be at most ${errors['maxlength'].requiredLength} characters.`;
  if (errors['passwordStrength']) return 'Use 8-72 characters with at least one letter and one digit.';
  if (errors['pattern']) return 'Invalid format.';
  if (errors['mismatch']) return 'Passwords do not match.';
  return 'Invalid value.';
}

export const passwordStrength: ValidatorFn = (control): ValidationErrors | null =>
  !control.value || PASSWORD_PATTERN.test(control.value) ? null : { passwordStrength: true };

export function matchesControl(otherName: string): ValidatorFn {
  return (control): ValidationErrors | null => {
    const other = control.parent?.get(otherName);
    return other && control.value !== other.value ? { mismatch: true } : null;
  };
}

/** Maps backend fieldErrors onto form controls. Returns true if at least one field error was applied. */
export function applyServerFieldErrors(form: FormGroup, error: ApiError): boolean {
  let applied = false;
  for (const fieldError of error.fieldErrors) {
    const control = form.get(fieldError.field);
    if (control) {
      control.setErrors({ ...(control.errors ?? {}), server: fieldError.message });
      control.markAsTouched();
      applied = true;
    }
  }
  return applied;
}
