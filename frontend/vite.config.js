import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
  build: {
    sourcemap: false,
    // No data: URIs: the CSP allows fonts and images from 'self' only.
    assetsInlineLimit: 0,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('recharts') || id.includes('d3-')) return 'charts';
          if (
            id.includes('react-markdown') ||
            id.includes('micromark') ||
            id.includes('mdast') ||
            id.includes('hast')
          )
            return 'markdown';
          return undefined;
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    // Tests talk only to a non-routable host (MSW answers); jsdom shares its origin to avoid CORS.
    env: { VITE_API_BASE_URL: 'http://api.test' },
    environmentOptions: { jsdom: { url: 'http://api.test' } },
    setupFiles: ['./src/test/setup.js'],
    css: false,
    include: ['src/**/*.test.{js,jsx}'],
  },
});
