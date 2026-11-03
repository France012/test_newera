import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getClaimDetail } from '../api/claims';
import { isApiError } from '../api/client';
import { ClaimStatusBadge } from '../components/ClaimStatusBadge';
import { DocumentUploader } from '../components/DocumentUploader';
import { EmptyState } from '../components/EmptyState';
import { ErrorBanner } from '../components/ErrorBanner';
import { ALLOWED_TRANSITIONS, isKnownStatus, statusLabel, type ClaimDetail } from '../types/claims';
import { formatBytes, formatDate, formatDateTime, shortId } from '../utils/format';

/**
 * Detail view of a single claim: the aggregated `{claim, documents}` payload of
 * `GET /bff/v1/claims/{id}`.
 *
 * A 404 gets its own "not found" treatment instead of a generic error banner: the resource
 * genuinely does not exist and retrying would not help.
 */
export function ClaimDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [detail, setDetail] = useState<ClaimDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const reload = useCallback(() => setReloadKey((value) => value + 1), []);

  useEffect(() => {
    if (!id) {
      setLoading(false);
      setError(new Error('Identificativo della pratica mancante.'));
      return undefined;
    }
    const controller = new AbortController();
    let active = true;
    setLoading(true);
    setError(null);

    getClaimDetail(id, controller.signal)
      .then((data) => {
        if (!active) {
          return;
        }
        setDetail(data);
        setLoading(false);
      })
      .catch((cause: unknown) => {
        if (!active || controller.signal.aborted) {
          return;
        }
        setDetail(null);
        setError(cause);
        setLoading(false);
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [id, reloadKey]);

  const notFound = isApiError(error) && error.isNotFound;

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Percorso di navigazione">
        <Link className="link" to="/claims">
          Pratiche
        </Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{detail?.claim.claimNumber ?? 'Dettaglio'}</span>
      </nav>

      {loading ? (
        <div className="card" aria-busy="true" aria-live="polite">
          <p className="loading-text">Caricamento della pratica…</p>
        </div>
      ) : null}

      {!loading && notFound ? (
        <div className="card">
          <EmptyState
            title="Pratica non trovata"
            description="La pratica richiesta non esiste o non è più disponibile."
            action={
              <Link className="button button--secondary" to="/claims">
                Torna all’elenco
              </Link>
            }
          />
        </div>
      ) : null}

      {!loading && error && !notFound ? <ErrorBanner error={error} onRetry={reload} /> : null}

      {!loading && detail ? (
        <>
          <header className="page__header page__header--stack">
            <div>
              <div className="page__title-row">
                <h1 className="page__title">{detail.claim.claimNumber}</h1>
                <ClaimStatusBadge status={detail.claim.status} />
              </div>
              <p className="page__subtitle">
                Polizza {detail.claim.policyNumber} · sinistro del{' '}
                {formatDate(detail.claim.incidentDate)}
              </p>
            </div>
          </header>

          <div className="detail-grid">
            <section className="card" aria-label="Dati della pratica">
              <div className="card__header">
                <h2 className="card__title">Dati della pratica</h2>
              </div>
              <dl className="definition-list">
                <div className="definition-list__item">
                  <dt>Identificativo</dt>
                  <dd className="mono" title={detail.claim.id}>
                    {shortId(detail.claim.id, 12)}
                  </dd>
                </div>
                <div className="definition-list__item">
                  <dt>Polizza</dt>
                  <dd>{detail.claim.policyNumber}</dd>
                </div>
                <div className="definition-list__item">
                  <dt>Cliente</dt>
                  <dd className="mono" title={detail.claim.customerId}>
                    {shortId(detail.claim.customerId, 12)}
                  </dd>
                </div>
                <div className="definition-list__item">
                  <dt>Tipo di sinistro</dt>
                  <dd>{detail.claim.claimType}</dd>
                </div>
                <div className="definition-list__item">
                  <dt>Data sinistro</dt>
                  <dd>{formatDate(detail.claim.incidentDate)}</dd>
                </div>
                <div className="definition-list__item">
                  <dt>Aperta il</dt>
                  <dd>{formatDateTime(detail.claim.openedAt)}</dd>
                </div>
                <div className="definition-list__item">
                  <dt>Ultimo aggiornamento</dt>
                  <dd>{formatDateTime(detail.claim.updatedAt)}</dd>
                </div>
              </dl>
              <div className="section-block">
                <h3 className="section-block__title">Descrizione</h3>
                <p className="prose">
                  {detail.claim.description || 'Nessuna descrizione fornita.'}
                </p>
              </div>
            </section>

            <aside className="card" aria-label="Azioni e ciclo di vita">
              <div className="card__header">
                <h2 className="card__title">Ciclo di vita</h2>
                <p className="card__subtitle">Stato corrente e transizioni previste dal dominio</p>
              </div>
              <p className="detail-status-line">
                <ClaimStatusBadge status={detail.claim.status} />
              </p>
              <Transitions status={detail.claim.status} />
              <p className="note">
                Le transizioni di stato sono gestite da core-app e non sono ancora esposte dal BFF:
                in fase 1 questa vista è di sola lettura.
              </p>
            </aside>
          </div>

          <section className="card" aria-label="Documenti allegati">
            <div className="card__header">
              <h2 className="card__title">Documenti allegati</h2>
              <p className="card__subtitle">
                {detail.documents.length === 0
                  ? 'Nessun documento collegato'
                  : `${detail.documents.length} documenti collegati`}
              </p>
            </div>

            {detail.documents.length === 0 ? (
              <EmptyState
                title="Nessun documento"
                description="La pratica non ha ancora allegati. I documenti caricati compariranno qui."
              />
            ) : (
              <ul className="document-list">
                {detail.documents.map((document) => (
                  <li className="document-list__item" key={document.id}>
                    <div className="document-list__main">
                      <p className="document-list__name">{document.fileName}</p>
                      <p className="document-list__meta">
                        {document.documentType} · {formatBytes(document.sizeBytes)} ·{' '}
                        {formatDateTime(document.uploadedAt)}
                      </p>
                    </div>
                    <div className="document-list__side">
                      <span className="document-list__badge">{document.contentType}</span>
                      <span
                        className="document-list__checksum mono"
                        title={document.checksumSha256}
                      >
                        sha256 {shortId(document.checksumSha256, 10)}
                      </span>
                    </div>
                  </li>
                ))}
              </ul>
            )}

            <div className="section-block">
              <DocumentUploader />
            </div>
          </section>
        </>
      ) : null}
    </div>
  );
}

/** Lists the transitions the domain allows from the current state (blueprint §2.3). */
function Transitions({ status }: { status: string }) {
  if (!isKnownStatus(status)) {
    return (
      <p className="note">Stato «{status}» non riconosciuto da questa versione dell’interfaccia.</p>
    );
  }
  const next = ALLOWED_TRANSITIONS[status];
  if (next.length === 0) {
    return <p className="note">La pratica è in uno stato finale: non sono previste transizioni.</p>;
  }
  return (
    <ul className="transition-list">
      {next.map((candidate) => (
        <li className="transition-list__item" key={candidate}>
          <span aria-hidden="true">→</span> {statusLabel(candidate)}
        </li>
      ))}
    </ul>
  );
}
