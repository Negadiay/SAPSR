import { StatusIcon } from './Icon';

// Ход работы по шагам: что уже проверено и что дальше. Каждая строка — иконка, название шага и итог словами.
export default function CheckSteps({ steps }) {
  return (
    <ol className="steps">
      {steps.map(({ title, tone, text }) => (
        <li key={title} className={`step step-${tone}`}>
          <StatusIcon tone={tone} />
          <span className="step-title">{title}</span>
          <span className="step-text">{text}</span>
        </li>
      ))}
    </ol>
  );
}
