/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the BFF, e.g. `http://localhost:8185`. Empty means same-origin. */
  readonly VITE_BFF_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
