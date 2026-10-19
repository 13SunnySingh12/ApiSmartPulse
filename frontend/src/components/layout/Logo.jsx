/** Simple geometric mark: a pulse line inside a clay-style rounded square. */
export function Logo({ size = 32 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" className="logo">
      <rect x="1" y="1" width="30" height="30" rx="9" className="logo__bg" />
      <path
        d="M6 17h5l2.5-6 4 11 2.5-5H26"
        fill="none"
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
        className="logo__line"
      />
    </svg>
  );
}
