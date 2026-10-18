import { isTrustedCheckoutUrl } from './payment-api';

describe('isTrustedCheckoutUrl', () => {
  const origin = 'http://localhost:4200';

  it.each([
    ['http://localhost:4200/sandbox-gateway/checkout/cs_1', true],
    ['/sandbox-gateway/checkout/cs_1', true],
    ['https://evil.example/sandbox-gateway/checkout/cs_1', false],
    ['http://localhost:4200/profile', false],
    ['javascript:alert(1)', false],
    ['//evil.example/sandbox-gateway/checkout/x', false],
    [null, false],
  ])('%s -> %s', (url, expected) => {
    expect(isTrustedCheckoutUrl(url as string | null, origin)).toBe(expected);
  });
});
