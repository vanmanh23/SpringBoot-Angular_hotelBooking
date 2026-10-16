import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-forbidden-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Access denied</h1>
    <p>You do not have permission to view this page.</p>
    <a routerLink="/">Back to home</a>
  `,
})
export class ForbiddenPage {}
