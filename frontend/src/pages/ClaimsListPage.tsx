import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { DEFAULT_PAGE_SIZE, listClaims } from '../api/claims';
import { ClaimsTable } from '../components/ClaimsTable';
import { EmptyState } from '../components/EmptyState';
import { ErrorBanner } from '../components/ErrorBanner';
import { CLAIM_STATUSES, STATUS_LABELS, type ClaimSummary } from '../types/claims';

/**
 * Paged claim list with a status filter.
 *
 * The filter is stored in the URL (`?status=APPROVED`) so the dashboard can deep-link into a
 * filtered view and the browser back button behaves as the operator expects. The page index
 * stays local and resets whenever the filter changes.
 */
export function ClaimsListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const rawStatus = searchParams.get('status') ?? '';
  const status = rawStatus === '' ? '' : rawStatus;

  const [claims, setClaims] = useState<ClaimSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [page, setPage] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);

  const reload = useCallback(() => setReloadKey((value) => value + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setLoading(true);
    setError(null);

    listClaims({
      status: status === '' ? undefined : status,
      page,
      size: DEFAULT_PAGE_SIZE,
      signal: controller.signal,
    })
      .then((data) => {
        if (!active) {
          return;
        }
        setClaims(Array.isArray(data) ? data : []);
        setLoading(false);
      })
      .catch((cause: unknown) => {
        if (!active || controller.signal.aborted) {
          return;
        }
        setError(cause);
        setClaims([]);
        setLoading(false);
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [status, page, reloadKey]);

  // The BFF exposes core-app's `content` only: there is no total on the wire, so a full page
  // is the only signal that more data may exist.
  const hasNextPage = claims.length === DEFAULT_PAGE_SIZE;
  const hasPreviousPage = page > 0;

  const handleStatusChange = useCallback(
    (value: string) => {
      setPage(0);
      if (value === '') {
        setSearchParams({}, { replace: true });
      } else {
        setSearchParams({ status: value }, { replace: true });
      }
    },
    [setSearchParams],
  );

  const subtitle = useMemo(() => {
    if (status === '') {
      return 'Tutte le pratiche del perimetro, ordinate per data di apertura.';
    }
    const label = CLAIM_STATUSES.find((candidate) => candidate === status);
    return `Pratiche filtrate per stato: ${label ? STATUS_LABELS[label] : status}.`;
  }, [status]);

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Pratiche</h1>
          <p className="page__subtitle">{subtitle}</p>
        </div>
        <div className="field field--inline">
          <label className="field__label" htmlFor="status-filter">
            Stato
          </label>
          <select
            id="status-filter"
            className="field__control"
            value={status}
            onChange={(event) => handleStatusChange(event.target.value)}
          >
            <option value="">Tutti gli stati</option>
            {CLAIM_STATUSES.map((candidate) => (
              <option value={candidate} key={candidate}>
                {STATUS_LABELS[candidate]}
              </option>
            ))}
          </select>
        </div>
      </header>

      {error ? <ErrorBanner error={error} onRetry={reload} /> : null}

      {loading ? (
        <div className="card" aria-busy="true" aria-live="polite">
          <p className="loading-text">Caricamento pratiche…</p>
        </div>
      ) : null}

      {!loading && !error && claims.length === 0 ? (
        <div className="card">
          <EmptyState
            title="Nessuna pratica trovata"
            description={
              status === ''
                ? 'Non risultano pratiche acquisite nel perimetro.'
                : 'Nessuna pratica corrisponde al filtro selezionato. Prova a rimuoverlo.'
            }
            action={
              status === '' ? undefined : (
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => handleStatusChange('')}
                >
                  Rimuovi filtro
                </button>
              )
            }
          />
        </div>
      ) : null}

      {!loading && claims.length > 0 ? (
        <div className="card card--flush">
          <ClaimsTable claims={claims} />
        </div>
      ) : null}

      {!loading && (claims.length > 0 || hasPreviousPage) ? (
        <nav className="pagination" aria-label="Paginazione pratiche">
          <button
            type="button"
            className="button button--ghost"
            onClick={() => setPage((current) => Math.max(0, current - 1))}
            disabled={!hasPreviousPage}
          >
            ← Precedente
          </button>
          <span className="pagination__status">Pagina {page + 1}</span>
          <button
            type="button"
            className="button button--ghost"
            onClick={() => setPage((current) => current + 1)}
            disabled={!hasNextPage}
          >
            Successiva →
          </button>
        </nav>
      ) : null}
    </div>
  );
}
