import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { LoginPage } from './login.page';

describe('LoginPage', () => {
  let backend: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  function setup() {
    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const type = (id: string, value: string) => {
      const input = el.querySelector<HTMLInputElement>(`#${id}`)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    const submit = () => {
      el.querySelector('form')!.dispatchEvent(new Event('submit'));
      fixture.detectChanges();
    };
    return { fixture, el, type, submit };
  }

  it('shows validation errors and sends nothing when the form is invalid', () => {
    const { el, type, submit } = setup();
    type('email', 'not-an-email');
    submit();

    expect(el.textContent).toContain('Enter a valid email address.');
    expect(el.textContent).toContain('This field is required.');
    backend.expectNone('/api/v1/auth/login');
  });

  it('shows a generic message on invalid credentials and keeps the form values', async () => {
    const { fixture, el, type, submit } = setup();
    type('email', 'a@example.com');
    type('password', 'wrong-pass1');
    submit();

    backend.expectOne('/api/v1/auth/login').flush(
      { code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' },
      { status: 401, statusText: 'Unauthorized' },
    );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el.querySelector('[role=alert]')?.textContent).toContain('Incorrect email or password.');
    expect(el.querySelector<HTMLInputElement>('#email')!.value).toBe('a@example.com');
  });

  it('prevents double submission while a request is in flight', () => {
    const { el, type, submit } = setup();
    type('email', 'a@example.com');
    type('password', 'Passw0rd-good');
    submit();
    submit();

    const requests = backend.match('/api/v1/auth/login');
    expect(requests.length).toBe(1);
    expect(el.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    requests[0].flush(null, { status: 500, statusText: 'Server Error' });
  });

  it('navigates to a safe return URL after login', async () => {
    const { fixture, type, submit } = setup();
    fixture.componentRef.setInput('returnUrl', '//evil.example');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    type('email', 'a@example.com');
    type('password', 'Passw0rd-good');
    submit();

    backend.expectOne('/api/v1/auth/login').flush({
      accessToken: 't',
      tokenType: 'Bearer',
      expiresIn: 900,
      user: { id: 'u', email: 'a@example.com', fullName: 'A', phone: null, status: 'ACTIVE', roles: ['CUSTOMER'], createdAt: '' },
    });
    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith('/');
  });
});
