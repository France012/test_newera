import { useId, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { formatBytes } from '../utils/format';

/** 20 MB - the multipart limit of document-service (blueprint §2.4). */
export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

const ACCEPTED_EXTENSIONS = ['pdf', 'png', 'jpg', 'jpeg'] as const;

export interface DocumentUploaderProps {
  /**
   * Upload handler. Phase 1 has no upload endpoint behind the BFF
   * (`BffClaimController` is read-only), so the page does not provide one and the
   * component renders in "not wired" mode instead of pretending to work.
   */
  onUpload?: (file: File) => Promise<void>;
  maxSizeBytes?: number;
  disabled?: boolean;
  /** Overrides the default explanation shown when {@link onUpload} is missing. */
  unavailableReason?: string;
}

type LocalState = { status: 'idle' } | { status: 'uploading' } | { status: 'done'; name: string };

/**
 * File picker for claim attachments.
 *
 * Validation runs client-side against the same limits document-service enforces
 * (20 MB, PDF/PNG/JPEG) so the operator gets immediate feedback, but the server stays the
 * authority: nothing here is a security control.
 */
export function DocumentUploader({
  onUpload,
  maxSizeBytes = MAX_UPLOAD_BYTES,
  disabled = false,
  unavailableReason,
}: DocumentUploaderProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const inputId = useId();
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [state, setState] = useState<LocalState>({ status: 'idle' });

  const wiringMissing = !onUpload;
  const isDisabled = disabled || wiringMissing || state.status === 'uploading';

  function validate(candidate: File): string | null {
    const extension = candidate.name.split('.').pop()?.toLowerCase() ?? '';
    if (!(ACCEPTED_EXTENSIONS as readonly string[]).includes(extension)) {
      return 'Formato non supportato: carica un PDF, PNG o JPEG.';
    }
    if (candidate.size === 0) {
      return 'Il file è vuoto.';
    }
    if (candidate.size > maxSizeBytes) {
      return `Il file supera il limite di ${formatBytes(maxSizeBytes)}.`;
    }
    return null;
  }

  function handleChange(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0] ?? null;
    setState({ status: 'idle' });
    if (!selected) {
      setFile(null);
      setError(null);
      return;
    }
    const validationError = validate(selected);
    setError(validationError);
    setFile(validationError ? null : selected);
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!file || !onUpload) {
      return;
    }
    setState({ status: 'uploading' });
    setError(null);
    try {
      await onUpload(file);
      setState({ status: 'done', name: file.name });
      setFile(null);
      if (inputRef.current) {
        inputRef.current.value = '';
      }
    } catch {
      setState({ status: 'idle' });
      setError('Caricamento non riuscito. Riprova.');
    }
  }

  return (
    <form className="uploader" onSubmit={handleSubmit}>
      <label className="uploader__label" htmlFor={inputId}>
        Allega un documento
      </label>
      <p className="uploader__hint">
        PDF, PNG o JPEG · massimo {formatBytes(maxSizeBytes)} per file.
      </p>

      <div className="uploader__row">
        <input
          ref={inputRef}
          id={inputId}
          className="uploader__input"
          type="file"
          accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
          onChange={handleChange}
          disabled={isDisabled}
          aria-describedby={error ? `${inputId}-error` : undefined}
        />
        <button
          type="submit"
          className="button button--primary"
          disabled={isDisabled || !file}
          title={wiringMissing ? 'Endpoint di upload non disponibile in fase 1' : undefined}
        >
          {state.status === 'uploading' ? 'Caricamento…' : 'Carica'}
        </button>
      </div>

      {file ? <p className="uploader__selected">Selezionato: {file.name}</p> : null}

      {error ? (
        <p className="uploader__error" id={`${inputId}-error`} role="alert">
          {error}
        </p>
      ) : null}

      {state.status === 'done' ? (
        <p className="uploader__success" role="status">
          Documento «{state.name}» caricato correttamente.
        </p>
      ) : null}

      {wiringMissing ? (
        <p className="uploader__notice">
          {unavailableReason ??
            'Il caricamento sarà attivo quando il BFF esporrà l’endpoint di upload: in fase 1 il BFF è in sola lettura.'}
        </p>
      ) : null}
    </form>
  );
}
