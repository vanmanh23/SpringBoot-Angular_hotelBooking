import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { AbstractControl } from '@angular/forms';
import { controlErrorMessage } from './form-errors';

/** Shows the validation message of a control once the user has interacted with it. */
// Reactive-form control state (touched/dirty/errors) is not signal-based, so this tiny leaf is checked with its
// parent on every pass instead of OnPush; otherwise errors would not appear after blur.
@Component({
  selector: 'app-field-error',
  // eslint-disable-next-line @angular-eslint/prefer-on-push-component-change-detection
  changeDetection: ChangeDetectionStrategy.Default,
  template: `
    @if (control().invalid && (control().touched || control().dirty)) {
      <p class="field-error" [id]="id()" role="alert">{{ message() }}</p>
    }
  `,
  styles: `.field-error { margin: 4px 0 0; color: var(--color-danger); font-size: 0.875rem; }`,
})
export class FieldError {
  readonly control = input.required<AbstractControl>();
  readonly id = input<string>('');

  protected message(): string | null {
    return controlErrorMessage(this.control());
  }
}
