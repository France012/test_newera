import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getDashboardSummary } from '../api/claims';
import { EmptyState } from '../components/EmptyState';
import { ErrorBanner } from '../components/ErrorBanner';
import { CLAIM_STATUSES, isOpenStatus, statusLabel, type DashboardSummary } from '../types/claims';

/** Statuses highlighted as KPI cards; the full distribution is rendered below. */
const KPI_STATUSES = ['APPROVED', 'REJECTED', 'CLOSED'] as const;

/**
 * Landing page: counters per lifecycle state and a shortcut into the filtered claim list.
 *
 * The BFF returns `{totalOpen, byStatus{}}` only - no time series, no deltas - so no chart is
 * drawn that the API could not actually support.
 */
export function DashboardPage() {
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const reload = useCallback(() => setReloadKey((value) => value + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setLoading(true);
    setError(null);

    getDashboardSummary(controller.signal)
      .then((data) => {
        if (!active) {
          return;
        }
        setSummary(data);
        setLoading(false);
      })
      .catch((cause: unknown) => {
        if (!active || controller.signal.aborted) {
          return;
        }
        setError(cause);
        setLoading(false);
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [reloadKey]);

  const byStatus = summary?.byStatus ?? {};
  const totalClaims = Object.values(byStatus).reduce((sum, count) => sum + count, 0);
  const maxCount = Math.max(1, ...Object.values(byStatus));

  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">Dashboard</h1>
          <p className="page__subtitle">
            Quadro di sintesi delle pratiche gestite dal perimetro assicurativo.
          </p>
        </div>
        <Link className="button button--secondary" to="/claims">
          Vai alle pratiche
        </Link>
      </header>

      {error ? <ErrorBanner error={error} onRetry={reload} /> : null}

      {loading && !summary ? (
        <div className="kpi-grid" aria-busy="true" aria-live="polite">
          {[0, 1, 2, 3].map((index) => (
            <div className="card card--kpi skeleton" key={index}>
              <span className="skeleton__line skeleton__line--short" />
              <span className="skeleton__line skeleton__line--value" />
            </div>
          ))}
        </div>
      ) : null}

      {summary ? (
        <>
          <section className="kpi-grid" aria-label="Indicatori principali">
            <article className="card card--kpi card--accent">
              <p className="kpi__label">Pratiche aperte</p>
              <p className="kpi__value">{summary.totalOpen.toLocaleString('it-IT')}</p>
              <p className="kpi__meta">Bozze, inviate, in lavorazione e integrazioni</p>
            </article>
            <article className="card card--kpi">
              <p className="kpi__label">Totale pratiche</p>
              <p className="kpi__value">{totalClaims.toLocaleString('it-IT')}</p>
              <p className="kpi__meta">Tutti gli stati del ciclo di vita</p>
            </article>
            {KPI_STATUSES.map((status) => (
              <article className="card card--kpi" key={status}>
                <p className="kpi__label">{statusLabel(status)}</p>
                <p className="kpi__value">{(byStatus[status] ?? 0).toLocaleString('it-IT')}</p>
                <p className="kpi__meta">
                  <Link className="link" to={`/claims?status=${status}`}>
                    Vedi elenco
                  </Link>
                </p>
              </article>
            ))}
          </section>

          <section className="card" aria-label="Distribuzione per stato">
            <div className="card__header">
              <h2 className="card__title">Distribuzione per stato</h2>
              <p className="card__subtitle">
                Numero di pratiche per ciascuno stato del ciclo di vita
              </p>
            </div>

            {totalClaims === 0 ? (
              <EmptyState
                title="Nessuna pratica presente"
                description="Non risultano pratiche acquisite: la distribuzione apparirà qui alla prima apertura."
              />
            ) : (
              <ul className="distribution">
                {CLAIM_STATUSES.map((status) => {
                  const count = byStatus[status] ?? 0;
                  const width = Math.round((count / maxCount) * 100);
                  return (
                    <li className="distribution__row" key={status}>
                      <span className="distribution__label">
                        {statusLabel(status)}
                        {isOpenStatus(status) ? (
                          <span className="distribution__flag" title="Richiede lavorazione">
                            aperta
                          </span>
                        ) : null}
                      </span>
                      <span
                        className="distribution__bar"
                        role="img"
                        aria-label={`${statusLabel(status)}: ${count} pratiche`}
                      >
                        <span
                          className={`distribution__fill distribution__fill--${status.toLowerCase()}`}
                          style={{ width: `${count === 0 ? 0 : Math.max(width, 3)}%` }}
                        />
                      </span>
                      <span className="distribution__count">{count.toLocaleString('it-IT')}</span>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        </>
      ) : null}
    </div>
  );
}
