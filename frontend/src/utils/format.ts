/**
 * Presentation helpers shared by the views.
 *
 * Kept separate from the components so the formatting rules (locale, units) are defined
 * once and the components stay about layout only.
 */

const DATE_FORMATTER = new Intl.DateTimeFormat('it-IT', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
});

const DATE_TIME_FORMATTER = new Intl.DateTimeFormat('it-IT', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

/** Locale date, e.g. `12 set 2024`. Returns an em dash for missing values. */
export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : DATE_FORMATTER.format(date);
}

/** Locale date and time, e.g. `12 set 2024, 14:30`. */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : DATE_TIME_FORMATTER.format(date);
}

/** Human readable size, e.g. `1,4 MB`. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) {
    return '—';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['kB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  const rounded = value >= 10 ? Math.round(value) : Math.round(value * 10) / 10;
  return `${rounded.toLocaleString('it-IT')} ${units[unitIndex]}`;
}

/** Short identifier for dense table cells, e.g. `3f2a1c9d…`. */
export function shortId(id: string | null | undefined, length = 8): string {
  if (!id) {
    return '—';
  }
  return id.length <= length ? id : `${id.slice(0, length)}…`;
}
