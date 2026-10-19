// Applies a remembered manual theme before React renders, avoiding a light→dark flash.
// An external module (not inline) so the strict CSP (script-src 'self') still holds.
try {
  const t = window.localStorage.getItem('asp-theme');
  if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t);
} catch {
  /* storage unavailable: follow the OS theme */
}
