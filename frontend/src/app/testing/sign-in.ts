import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { Role } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';

/** Test helper: signs in through the real AuthService with the given roles (only the UI reacts to roles). */
export async function signInAs(backend: HttpTestingController, roles: Role[], id = 'u-self'): Promise<void> {
  const done = firstValueFrom(TestBed.inject(AuthService).login({ email: 'a@example.com', password: 'x' }));
  backend.expectOne('/api/v1/auth/login').flush({
    accessToken: 'token', tokenType: 'Bearer', expiresIn: 900,
    user: { id, email: 'a@example.com', fullName: 'Test User', phone: null, status: 'ACTIVE', roles, createdAt: '' },
  });
  await done;
}

export const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
