import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/** Uniform error state with a retry action, so a failed load never leaves a dead end. */
@Component({
  selector: 'app-load-error',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (message()) {
      <div class="alert alert-error load-error" role="alert">
        <span>{{ message() }}</span>
        <button class="btn btn-secondary btn-small" type="button" (click)="retry.emit()">Try again</button>
      </div>
    }
  `,
  styles: `.load-error { display: flex; gap: 12px; align-items: center; justify-content: space-between; flex-wrap: wrap; }`,
})
export class LoadError {
  readonly message = input<string | null>(null);
  readonly retry = output<void>();
}
