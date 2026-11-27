// Замечания автоматической проверки из поля format_errors (форма v1, у новых работ — с полями v2).
// Содержательные — от языковой модели и правил над её фактами: category content или requirements
// либо origin не symbolic. Они сдачу не блокируют (решение 5).

const parseFindings = (raw) => {
  if (Array.isArray(raw)) return raw;
  if (!raw) return [];
  try {
    const data = JSON.parse(raw);
    return Array.isArray(data) ? data.filter((f) => f && typeof f === 'object') : [];
  } catch { return []; }
};

const isContentFinding = (f) =>
  f.category === 'content' || f.category === 'requirements' || (f.origin != null && f.origin !== 'symbolic');

export const isError = (f) => f.severity === 'critical' || f.severity === 'major';

// Кто нашёл замечание — честно перед студентом и наглядно для защиты.
export const ORIGIN_LABELS = {
  symbolic: 'найдено правилами',
  neural: 'найдено нейросетью',
  hybrid: 'нейросеть и правила',
};

const SEVERITY_LABELS = { critical: 'критическая ошибка', major: 'ошибка', info: 'примечание' };
export const severityLabel = (f) => SEVERITY_LABELS[f.severity] || 'предупреждение';

// Оформление (сначала ошибки) и содержание по отдельности.
export const splitFindings = (raw) => {
  const all = parseFindings(raw);
  const content = all.filter(isContentFinding);
  const format = all.filter((f) => !isContentFinding(f));
  format.sort((a, b) => Number(isError(b)) - Number(isError(a)));
  return { format, content };
};

export const summarizeFindings = (raw) => {
  const { format, content } = splitFindings(raw);
  return {
    formatErrors: format.filter(isError).length,
    formatWarnings: format.filter((f) => !isError(f)).length,
    content,
    needsReview: content.filter((f) => f.needs_teacher_review).length,
  };
};

// Где в документе: «стр. 2–8» или то, что прислал обработчик.
export const findingPlace = (f) => {
  if (f.page != null && f.page !== '') return `стр. ${f.page}`;
  return f.location && f.location !== 'Документ' ? f.location : '';
};
