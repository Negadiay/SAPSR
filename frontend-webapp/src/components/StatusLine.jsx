import { StatusIcon } from './Icon';

// Состояние с иконкой и текстом: цвет не единственный признак — рядом всегда слова.
// role="alert" — для ошибок, о которых программа чтения с экрана должна сказать сразу.
export default function StatusLine({ tone, children, className = '', role }) {
  return (
    <div className={`status-line status-line-${tone} ${className}`.trim()} role={role}>
      <StatusIcon tone={tone} />
      <span>{children}</span>
    </div>
  );
}
