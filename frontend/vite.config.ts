import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * Vite configuration for the claims frontend.
 *
 * Ports follow blueprint §2.9: dev server on 5199, preview on 5198 (never 3000/5173).
 * The `/bff` proxy makes the dev server same-origin with the BFF, so the browser never
 * performs a cross-origin call during development and CORS stays irrelevant locally.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5199,
    strictPort: true,
    proxy: {
      '/bff': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8185',
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 5198,
    strictPort: true,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    restoreMocks: true,
    unstubGlobals: true,
    css: false,
  },
});
