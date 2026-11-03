import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

/**
 * Global test setup (referenced by `vite.config.ts` -> `test.setupFiles`).
 *
 * `cleanup` is registered explicitly because Vitest only auto-cleans when `globals` and the
 * Testing Library auto-cleanup hook agree; being explicit keeps the suite correct either way.
 */
afterEach(() => {
  cleanup();
});
