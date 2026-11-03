import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { ApiError, apiGet, buildUrl, clientConfig, isApiError, request } from './client';

/**
 * Minimal `Response` stand-in: the client only reads `ok`, `status` and `text()`, so there is
 * no reason to depend on a real fetch implementation here.
 */
function fakeResponse(init: { status?: number; body?: string } = {}): Response {
  const status = init.status ?? 200;
  return {
    ok: status >= 200 && status < 300,
    status,
    text: () => Promise.resolve(init.body ?? ''),
  } as unknown as Response;
}

function problemResponse(status: number, body: Record<string, unknown>): Response {
  return fakeResponse({ status, body: JSON.stringify(body) });
}

describe('buildUrl', () => {
  const originalBaseUrl = clientConfig.baseUrl;

  afterEach(() => {
    clientConfig.baseUrl = originalBaseUrl;
  });

  it('keeps the path relative when no base URL is configured (same-origin)', () => {
    clientConfig.baseUrl = '';
    expect(buildUrl('/bff/v1/claims')).toBe('/bff/v1/claims');
  });

  it('prefixes the configured base URL without doubling the slash', () => {
    clientConfig.baseUrl = 'http://localhost:8185/';
    expect(buildUrl('/bff/v1/claims')).toBe('http://localhost:8185/bff/v1/claims');
  });

  it('serialises query parameters and drops empty ones', () => {
    clientConfig.baseUrl = '';
    const url = buildUrl('/bff/v1/claims', {
      status: 'APPROVED',
      page: 0,
      size: 20,
      empty: '',
      missing: undefined,
      nothing: null,
    });
    expect(url).toBe('/bff/v1/claims?status=APPROVED&page=0&size=20');
  });

  it('omits the question mark when every parameter is empty', () => {
    expect(buildUrl('/bff/v1/dashboard/summary', { status: '' })).toBe('/bff/v1/dashboard/summary');
  });
});

describe('request', () => {
  beforeEach(() => {
    clientConfig.baseUrl = '';
  });

  it('decodes the JSON body of a successful response', async () => {
    const fetchMock = vi.fn().mockResolvedValue(fakeResponse({ body: '[{"id":"abc"}]' }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await apiGet<Array<{ id: string }>>('/bff/v1/claims');

    expect(result).toEqual([{ id: 'abc' }]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('/bff/v1/claims');
    expect((init as RequestInit).method).toBe('GET');
  });

  it('returns undefined for a 204 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(fakeResponse({ status: 204 })));

    await expect(apiGet('/bff/v1/claims/x')).resolves.toBeUndefined();
  });

  it('turns an RFC 9457 problem body into an ApiError carrying the operator message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        problemResponse(404, {
          type: 'about:blank',
          title: 'Resource not found',
          status: 404,
          detail: 'The requested resource does not exist',
          instance: '/bff/v1/claims/123',
        }),
      ),
    );

    const error = await request('/bff/v1/claims/123').catch((cause: unknown) => cause);

    expect(isApiError(error)).toBe(true);
    const apiError = error as ApiError;
    expect(apiError.kind).toBe('http');
    expect(apiError.status).toBe(404);
    expect(apiError.title).toBe('Resource not found');
    expect(apiError.message).toBe('The requested resource does not exist');
    expect(apiError.isNotFound).toBe(true);
    expect(apiError.isRetryable).toBe(false);
  });

  it('falls back to a generic message when the error body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(fakeResponse({ status: 500, body: 'boom' })));

    const error = (await request('/bff/v1/claims').catch((cause: unknown) => cause)) as ApiError;

    expect(error.kind).toBe('http');
    expect(error.status).toBe(500);
    expect(error.message).toContain('500');
    expect(error.isRetryable).toBe(true);
  });

  it('maps a transport failure to a retryable network error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    const error = (await request('/bff/v1/claims').catch((cause: unknown) => cause)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.kind).toBe('network');
    expect(error.isRetryable).toBe(true);
  });

  it('aborts and reports a timeout when the service does not answer in time', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: unknown, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () => {
              reject(new DOMException('Aborted', 'AbortError'));
            });
          }),
      ),
    );

    const assertion = expect(request('/bff/v1/claims', { timeoutMs: 50 })).rejects.toMatchObject({
      kind: 'timeout',
    });
    await vi.advanceTimersByTimeAsync(60);
    await assertion;

    vi.useRealTimers();
  });
});
