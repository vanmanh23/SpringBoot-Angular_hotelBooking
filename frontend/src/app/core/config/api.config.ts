import { InjectionToken } from '@angular/core';

/**
 * Base URL of the API Gateway. Relative by default: `ng serve` proxies /api to the gateway
 * (proxy.conf.json) and the production nginx image does the same, so no CORS or hard-coded hosts.
 */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => '/api/v1',
});
