/**
 * Minimal `fetch` wrapper for the BFF (blueprint §2.8: no axios).
 *
 * Responsibilities kept deliberately narrow:
 *  - resolve the base URL from `VITE_BFF_BASE_URL` (empty = same-origin, the default,
 *    served by the Vite proxy in dev and by nginx in the container);
 *  - normalise every failure into a single {@link ApiError} shape, so components never
 *    have to inspect `Response` objects or guess at a `TypeError`;
 *  - decode the RFC 9457 `application/problem+json` bodies produced by the BFF's
 *    `GlobalExceptionHandler` into a message that can be shown to an operator.
 *
 * The BFF never relays downstream error bodies (it maps them to 502/503), so whatever
 * `detail` reaches this client is safe to display.
 */

/** Why a call failed, so the UI can pick the right recovery affordance. */
export type ApiErrorKind = 'http' | 'network' | 'timeout' | 'parse';

/** RFC 9457 problem body as emitted by the BFF and by Spring's `ProblemDetail`. */
interface ProblemDetailBody {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
}

/** Single error type surfaced to the components. */
export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number;
  readonly title: string;
  readonly detail: string;
  readonly instance?: string;

  constructor(init: {
    kind: ApiErrorKind;
    message: string;
    status?: number;
    title?: string;
    detail?: string;
    instance?: string;
  }) {
    super(init.message);
    this.name = 'ApiError';
    this.kind = init.kind;
    this.status = init.status ?? 0;
    this.title = init.title ?? '';
    this.detail = init.detail ?? '';
    if (init.instance !== undefined) {
      this.instance = init.instance;
    }
  }

  /** The resource genuinely does not exist (BFF maps a downstream 404 to a 404). */
  get isNotFound(): boolean {
    return this.kind === 'http' && this.status === 404;
  }

  /** A dependency is temporarily unavailable - retrying is meaningful. */
  get isRetryable(): boolean {
    return (
      this.kind === 'network' ||
      this.kind === 'timeout' ||
      (this.kind === 'http' && (this.status === 502 || this.status === 503 || this.status >= 500))
    );
  }
}

/** Narrows an unknown thrown value to {@link ApiError}. */
export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

/**
 * Mutable client configuration.
 * Exposed as an object (not a frozen constant) so tests can point the client at a stub
 * base URL without having to re-import the module.
 */
export const clientConfig = {
  baseUrl: (import.meta.env?.VITE_BFF_BASE_URL ?? '').trim().replace(/\/+$/, ''),
  timeoutMs: 10_000,
};

/** Query parameters; `undefined`, `null` and empty strings are dropped. */
export type QueryParams = Record<string, string | number | boolean | undefined | null>;

/** Appends the query string to `path`, encoding every value. */
export function buildUrl(path: string, query?: QueryParams): string {
  // Normalised here and not only at module init: `clientConfig.baseUrl` is mutable, so a
  // value assigned later (tests, runtime override) must not produce a double slash.
  const base = clientConfig.baseUrl.trim().replace(/\/+$/, '');
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  const url = `${base}${normalizedPath}`;
  if (!query) {
    return url;
  }
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    search.append(key, String(value));
  }
  const qs = search.toString();
  return qs ? `${url}?${qs}` : url;
}

/** Extracts the operator-facing message from a problem body, with a safe fallback. */
function describeFailure(body: ProblemDetailBody | undefined, status: number): string {
  if (body?.detail) {
    return body.detail;
  }
  if (body?.title) {
    return body.title;
  }
  return `Richiesta non riuscita (HTTP ${status}).`;
}

/** Parses a response body as JSON without ever throwing. */
async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) {
    return undefined;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  query?: QueryParams;
  body?: unknown;
  /** Overrides {@link clientConfig.timeoutMs}. */
  timeoutMs?: number;
  /** Caller cancellation (component unmount). */
  signal?: AbortSignal;
  headers?: Record<string, string>;
}

/**
 * Performs a request against the BFF and returns the decoded JSON body.
 *
 * @throws {ApiError} for HTTP errors, timeouts, network failures and undecodable bodies.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', query, body, timeoutMs = clientConfig.timeoutMs, signal } = options;

  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);

  const forwardAbort = () => controller.abort();
  if (signal) {
    if (signal.aborted) {
      controller.abort();
    } else {
      signal.addEventListener('abort', forwardAbort, { once: true });
    }
  }

  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  try {
    const response = await fetch(buildUrl(path, query), {
      method,
      headers,
      signal: controller.signal,
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    });

    if (!response.ok) {
      const parsed = await readJson(response);
      const problem =
        parsed !== null && typeof parsed === 'object' ? (parsed as ProblemDetailBody) : undefined;
      throw new ApiError({
        kind: 'http',
        status: response.status,
        message: describeFailure(problem, response.status),
        title: problem?.title ?? '',
        detail: problem?.detail ?? '',
        instance: problem?.instance,
      });
    }

    if (response.status === 204) {
      return undefined as T;
    }

    try {
      return (await readJson(response)) as T;
    } catch {
      throw new ApiError({
        kind: 'parse',
        status: response.status,
        message: 'La risposta del servizio non è leggibile.',
      });
    }
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }
    if (timedOut) {
      throw new ApiError({
        kind: 'timeout',
        message: 'Il servizio non ha risposto in tempo. Riprova tra qualche istante.',
      });
    }
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiError({ kind: 'network', message: 'Richiesta annullata.' });
    }
    throw new ApiError({
      kind: 'network',
      message: 'Impossibile contattare il servizio. Verifica la connessione e riprova.',
    });
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', forwardAbort);
  }
}

/** GET helper. */
export function apiGet<T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) {
  return request<T>(path, { ...options, method: 'GET' });
}
