import { describe, expect, it } from 'vitest';
import { neonProblem, safeNext } from './neonAuth.js';

describe('safeNext (open-redirect guard)', () => {
  it('keeps same-origin paths only', () => {
    expect(safeNext('/projects/PULSE/board')).toBe('/projects/PULSE/board');
    for (const bad of [
      '//evil.example',
      'https://evil.example',
      '/' + String.fromCharCode(92) + 'evil.example',
      'javascript:alert(1)',
      '',
      null,
    ]) {
      expect(safeNext(bad)).toBe('/projects');
    }
  });
});

describe('neonProblem', () => {
  it('maps provider errors without leaking their text', () => {
    expect(neonProblem({ status: 401, code: 'INVALID_EMAIL_OR_PASSWORD', message: 'internal' }).detail).toBe(
      'Email or password is incorrect.',
    );
    expect(neonProblem({ status: 422, code: 'USER_ALREADY_EXISTS' }).code).toBe('EMAIL_TAKEN');
    expect(neonProblem({ status: 400, code: 'PASSWORD_TOO_SHORT' }).errors[0].field).toBe('password');
    expect(neonProblem({ status: 429 }).code).toBe('RATE_LIMITED');
    expect(neonProblem(new TypeError('Failed to fetch')).code).toBe('AUTH_UNAVAILABLE');
    expect(neonProblem({ status: 400, code: 'SOMETHING', message: 'stack trace here' }).detail).toBe(
      'Sign-in failed. Try again.',
    );
  });
});
