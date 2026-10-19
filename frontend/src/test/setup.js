import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { server } from './utils.jsx';

// jsdom lacks these browser APIs used by Radix and the theme hook.
window.matchMedia ??= (query) => ({
  matches: false,
  media: query,
  addEventListener() {},
  removeEventListener() {},
});
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
Element.prototype.scrollIntoView ??= () => {};
Element.prototype.hasPointerCapture ??= () => false;

// Listen at import time: axios captures globalThis.fetch when it loads, so MSW must patch it first.
server.listen({ onUnhandledRequest: 'error' });
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());
