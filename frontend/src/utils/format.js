/** Formatting helpers. Timestamps are UTC from the API and shown in the browser's zone (BR-015). */
const dateFmt = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' });
const dateYearFmt = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
const dateTimeFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

/** Due dates are calendar dates (no time): parse as local midnight, not UTC. */
export function parseDate(iso) {
  if (!iso) return null;
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

export function formatDate(iso) {
  const d = parseDate(iso);
  if (!d) return '';
  return d.getFullYear() === new Date().getFullYear() ? dateFmt.format(d) : dateYearFmt.format(d);
}

export function formatDateTime(instant) {
  return instant ? dateTimeFmt.format(new Date(instant)) : '';
}

export function todayIso() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function addDaysIso(iso, days) {
  const d = parseDate(iso);
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function isOverdue(iso, status) {
  return Boolean(iso) && status !== 'DONE' && iso < todayIso();
}

export function hours(h) {
  if (h == null) return '';
  const n = Number(h);
  return `${Number.isInteger(n) ? n : n.toFixed(1)} h`;
}

export const pct = (p) => `${Math.round(p)}%`;
