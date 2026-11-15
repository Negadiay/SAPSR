// Отчёт v2 (docs/contracts/report-v2.md) — разбор для экранов студента и преподавателя.

// Пороги уверенности повторяют базу знаний обработчика (kb/profiles/base.yaml): ниже review_threshold
// замечание уходит на решение преподавателя. При смене порога в базе знаний — поменять и здесь.
export const HIGH_CONFIDENCE = 0.85;
export const REVIEW_THRESHOLD = 0.75;

export const confidenceLabel = (confidence) => {
  if (typeof confidence !== 'number') return '';
  if (confidence >= HIGH_CONFIDENCE) return 'уверенность высокая';
  if (confidence >= REVIEW_THRESHOLD) return 'уверенность средняя';
  return 'уверенность низкая, решит преподаватель';
};

// Кто нашёл замечание: коротко и с пояснением по нажатию.
export const ORIGINS = {
  symbolic: { label: 'правило', note: 'Найдено правилом по данным документа: размерам, шрифтам, структуре. Результат всегда один и тот же.' },
  neural: { label: 'модель', note: 'Найдено языковой моделью. Правилами не подтверждено — проверьте сами.' },
  hybrid: { label: 'правило + модель', note: 'Языковая модель нашла в тексте утверждения и цитаты, цитаты сверены с текстом, вывод сделало правило.' },
};

const CONTENT = new Set(['content', 'requirements']);
export const isContent = (f) => CONTENT.has(f.category) || (f.origin != null && f.origin !== 'symbolic');

// Счётчики сводки: оформление (вместе с целостностью файла), структура, содержание.
export const summarize = (findings) => {
  const counts = { format: 0, structure: 0, content: 0, review: 0 };
  for (const f of findings) {
    if (isContent(f)) counts.content += 1;
    else if (f.category === 'structure') counts.structure += 1;
    else counts.format += 1;
    if (f.needs_teacher_review) counts.review += 1;
  }
  return counts;
};

// Противоречие: правило над фактами модели нашло расхождение между разными разделами.
export const isContradiction = (f) => f.origin === 'hybrid' && Array.isArray(f.evidence)
  && new Set(f.evidence.filter((e) => e.quote).map((e) => e.section || `стр. ${e.page}`)).size > 1;

export const evidencePlace = (e) => [e.section ? `раздел «${e.section}»` : '', e.page ? `стр. ${e.page}` : '']
  .filter(Boolean).join(', ');

export const REQUIREMENT_STATUS = {
  satisfied: { label: 'выполнено', tone: 'ok' },
  violated: { label: 'нарушено', tone: 'error' },
  not_established: { label: 'не найдено', tone: 'warning' },
  disputed: { label: 'спорно, решит преподаватель', tone: 'warning' },
  not_applicable: { label: 'не на этом этапе', tone: 'muted' },
};

// Этапы проверки словами (queue-v2.md, раздел 4): для индикатора хода и сведений о проверке.
export const STAGE_LABELS = {
  extract: 'извлечение текста',
  structure: 'структура',
  format: 'оформление',
  claims: 'содержание: модель',
  reasoning: 'правила',
  report: 'отчёт',
};

const MODE_LABELS = {
  hybrid: 'правила и языковая модель',
  symbolic: 'только правила',
  neural: 'только языковая модель',
};
export const modeLabel = (mode) => MODE_LABELS[mode] || mode || '';

// Что не проверено: одинаковые сообщения — один раз.
export const skippedMessages = (report) => [
  ...new Set((report?.pipeline?.skipped || []).map((s) => s.message).filter(Boolean)),
];

export const formatMs = (ms) => {
  if (typeof ms !== 'number') return '';
  return ms < 1000 ? `${ms} мс` : `${(ms / 1000).toFixed(1).replace('.', ',')} с`;
};
