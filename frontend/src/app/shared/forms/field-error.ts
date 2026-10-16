import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { AbstractControl } from '@angular/forms';
import { controlErrorMessage } from './form-errors';

/** Shows the validation message of a control once the user has interacted with it. */
@Component({
  selector: 'app-field-error',
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
