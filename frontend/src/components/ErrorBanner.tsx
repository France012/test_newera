import { isApiError } from '../api/client';

export interface ErrorBannerProps {
  /** Whatever was thrown - the banner normalises it. */
  error: unknown;
  /** Heading; defaults to a generic failure message. */
  title?: string;
  /** When provided, a retry button is rendered. */
  onRetry?: () => void;
}

/**
 * Inline error surface.
 *
 * Displays the operator-facing `detail` of the BFF problem response (the BFF never relays
 * downstream bodies, so nothing internal leaks here) and offers a retry only when the
 * failure is plausibly transient.
 */
export function ErrorBanner({ error, title, onRetry }: ErrorBannerProps) {
  const { heading, message, retryable } = describe(error, title);
  const showRetry = Boolean(onRetry) && retryable;

  return (
    <div className="error-banner" role="alert">
      <svg
        className="error-banner__icon"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        aria-hidden="true"
      >
        <path
          d="M12 8.5v4.2M12 16.2h.01M10.3 3.9 2.6 17.2A1.9 1.9 0 0 0 4.3 20h15.4a1.9 1.9 0 0 0 1.7-2.8L13.7 3.9a1.9 1.9 0 0 0-3.4 0Z"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      <div className="error-banner__content">
        <p className="error-banner__title">{heading}</p>
        <p className="error-banner__message">{message}</p>
      </div>
      {showRetry ? (
        <button type="button" className="button button--ghost" onClick={onRetry}>
          Riprova
        </button>
      ) : null}
    </div>
  );
}

function describe(
  error: unknown,
  title?: string,
): {
  heading: string;
  message: string;
  retryable: boolean;
} {
  if (isApiError(error)) {
    return {
      heading: title ?? (error.title || DEFAULT_HEADING),
      message: error.message,
      retryable: error.isRetryable,
    };
  }
  if (error instanceof Error && error.message) {
    return { heading: title ?? DEFAULT_HEADING, message: error.message, retryable: false };
  }
  return {
    heading: title ?? DEFAULT_HEADING,
    message: 'Si è verificato un errore imprevisto. Riprova o contatta l’assistenza.',
    retryable: false,
  };
}

const DEFAULT_HEADING = 'Impossibile completare la richiesta';
