# `frontend` — Claims Suite Web UI

React 18 + TypeScript + Vite single-page application for the enterprise insurance claim
management suite. It is a **thin presentation layer**: every business rule lives in
`core-app`, and the browser only ever talks to the **BFF** (`/bff/v1/**`).

```text
browser ──► frontend (this module)
              │  same-origin /bff/v1/**            (Vite proxy in dev, nginx in the container)
              ▼
            bff (:8185) ──► core-app (:8180) + document-service (:8181)
```

## Stack

| Concern        | Choice                                                |
| -------------- | ----------------------------------------------------- |
| Runtime / view | React 18, React Router 6                              |
| Language       | TypeScript 5.5 (`strict`, `noUncheckedIndexedAccess`) |
| Build          | Vite 5                                                |
| Tests          | Vitest 2 + Testing Library + jsdom                    |
| Lint           | ESLint 9 flat config + typescript-eslint              |
| Format         | Prettier 3 (`singleQuote`, `printWidth` 100)          |
| HTTP           | `fetch` wrapper in `src/api/client.ts` — **no axios** |

## Commands

```bash
npm ci                 # install from the lockfile (what CI runs)
npm run dev            # dev server on http://localhost:5199  (never 3000/5173)
npm run build          # typecheck + production build into dist/
npm run preview        # preview the build on http://localhost:5198
npm run typecheck      # tsc --noEmit for the app and for the tooling files
npm run lint           # eslint, zero warnings tolerated
npm run format:check   # prettier check (CI gate)
npm run format         # prettier --write
npm run test           # vitest in watch mode
npm run test:run       # vitest run (CI gate)
```

## Environment

Only one public variable, read at build time (`src/vite-env.d.ts` types it). Copy
`.env.example` to `.env.local` if you need to override it.

| Variable            | Default                 | Meaning                                                               |
| ------------------- | ----------------------- | --------------------------------------------------------------------- |
| `VITE_BFF_BASE_URL` | _empty_                 | BFF origin. **Empty means same-origin**, which is the intended setup. |
| `VITE_PROXY_TARGET` | `http://localhost:8185` | Dev-server proxy target, used only when `VITE_BFF_BASE_URL` is empty. |

Keeping it empty is deliberate: in development the Vite proxy forwards `/bff` to the BFF, and in
the container nginx does the same. The browser therefore never makes a cross-origin request and
CORS configuration stays irrelevant. Set it explicitly only when the BFF lives on another origin,
and add that origin to the BFF's `BFF_ALLOWED_ORIGINS`.

**No secret, token or credential belongs in this module.** Anything prefixed with `VITE_` is
inlined into the public bundle and is readable by every visitor.

## Structure

```text
src/
├── api/
│   ├── client.ts          fetch wrapper: base URL, timeout, RFC 9457 error normalisation
│   └── claims.ts          typed BFF endpoints (listClaims, getClaimDetail, getDashboardSummary)
├── components/
│   ├── Layout.tsx         header, navigation, <main> landmark
│   ├── ClaimsTable.tsx    dense claim table
│   ├── ClaimStatusBadge.tsx
│   ├── DocumentUploader.tsx
│   ├── EmptyState.tsx
│   └── ErrorBanner.tsx
├── pages/
│   ├── DashboardPage.tsx      /
│   ├── ClaimsListPage.tsx     /claims[?status=STATUS]
│   └── ClaimDetailPage.tsx    /claims/:id
├── types/claims.ts        wire types + status labels + allowed transitions
├── utils/format.ts        it-IT date, byte and id formatting
├── styles/global.css      design tokens and all component styles
├── router.tsx             route table (Layout as shell)
├── App.tsx                RouterProvider
└── main.tsx               React root
```

## API contract used

The UI consumes exactly three endpoints (blueprint §2.5) and nothing else:

| Method | Path                        | Returns                                                                        |
| ------ | --------------------------- | ------------------------------------------------------------------------------ |
| GET    | `/bff/v1/claims`            | `ClaimSummary[]` — a **plain array**, plus `status`/`page`/`size` query params |
| GET    | `/bff/v1/claims/{id}`       | `{ claim, documents[] }`                                                       |
| GET    | `/bff/v1/dashboard/summary` | `{ totalOpen, byStatus{} }`                                                    |

Three consequences are reflected in the code and are **not** bugs:

1. **No page metadata.** The BFF forwards only core-app's `content`, so there is no
   `totalElements`. The list page enables "Successiva" when a full page came back; it never
   displays an invented total.
2. **`status` is a `string` on the wire.** The BFF intentionally does not re-implement the state
   machine. The UI keeps the `ClaimStatus` union locally and falls back to the raw value for
   unknown statuses instead of crashing.
3. **The BFF is read-only in phase 1.** There is no claim mutation and no document upload
   endpoint. `DocumentUploader` therefore validates files client-side (20 MB, PDF/PNG/JPEG — the
   same limits as document-service) but renders an explicit "not wired yet" notice instead of
   pretending to upload. When the upload endpoint lands, pass `onUpload` and the component works
   unchanged.

Errors follow RFC 9457: the BFF answers with `application/problem+json`, and `client.ts` turns
`title`/`detail` into an `ApiError` with `kind` (`http` | `network` | `timeout` | `parse`) so the
UI can distinguish "not found" from "retry later". The BFF never relays downstream bodies, so the
`detail` text is safe to show to an operator.

## Testing approach

Unit and component tests only — no browser, no backend, no Docker:

- `src/api/client.test.ts` — URL building, query serialisation, problem+json decoding, network
  failure, timeout via fake timers.
- `src/components/ClaimStatusBadge.test.tsx` — label mapping and graceful degradation on an
  unknown status.
- `src/pages/ClaimsListPage.test.tsx` — the page is tested against the **API module boundary**
  (`vi.mock('../api/claims')`), covering the happy path, the URL-driven filter, the empty state
  and the error banner.

There is no E2E suite in phase 1 (Playwright is out of scope by blueprint §6).

## CI

`.github/workflows/ci.yml` runs, from the repository root:

```bash
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run test -- --run
npm --prefix frontend run build
```

`dist/` is uploaded as a build artefact. The production image (`infra/docker/frontend.Dockerfile`,
nginx on port 8188) is built and signed in `container-images.yml` — **not** from this module.

`js-yaml` is a **direct** `devDependency`, not an incidental one. The `repo-hygiene` job of
`ci.yml` loads `./frontend/node_modules/js-yaml` to parse every YAML/OpenAPI snapshot (ADR-0009),
because this module is the only Node project in the repository (ADR-0008). It previously arrived
only as a transitive dependency of `@eslint/eslintrc`, so any ESLint upgrade that dropped it would
have broken the gate without touching a single line of this module. Keep it declared: if a
lockfile refresh ever removes it, re-add `npm install --save-dev js-yaml`.
