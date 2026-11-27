// Знак приложения: документ с галочкой. Цвета — из темы, поэтому знак виден и в тёмной, и в контрастной.
export default function AppLogo({ size = 64 }) {
  return (
    <svg className="app-logo" width={size} height={size} viewBox="0 0 64 64" aria-hidden="true" focusable="false">
      <rect width="64" height="64" rx="16" fill="var(--accent)" />
      <path d="M22 14h14l10 10v24a3 3 0 0 1-3 3H22a3 3 0 0 1-3-3V17a3 3 0 0 1 3-3Z" fill="var(--on-accent)" opacity="0.95" />
      <path d="M36 14v8a2 2 0 0 0 2 2h8" fill="none" stroke="var(--accent)" strokeWidth="2.5" strokeLinejoin="round" />
      <path d="m25.5 37 5 5 9-10" fill="none" stroke="var(--accent)" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
