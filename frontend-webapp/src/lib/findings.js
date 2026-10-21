// Замечания автоматической проверки из поля format_errors (результат обработчика в формате v1).
// Содержательные — от языковой модели и правил над её фактами: category content или requirements
// либо origin не symbolic. Они сдачу не блокируют (решение 5).

const parse = (raw) => {
  if (Array.isArray(raw)) return raw;
  if (!raw) return [];
  try {
    const data = JSON.parse(raw);
    return Array.isArray(data) ? data : [];
  } catch { return []; }
};

export const isContentFinding = (f) =>
  f.category === 'content' || f.category === 'requirements' || (f.origin != null && f.origin !== 'symbolic');

const isError = (f) => f.severity === 'critical' || f.severity === 'major';

export const ORIGIN_LABELS = { symbolic: 'правило', neural: 'модель', hybrid: 'правило + модель' };

export const summarizeFindings = (raw) => {
  const all = parse(raw);
  const content = all.filter(isContentFinding);
  const format = all.filter((f) => !isContentFinding(f));
  return {
    formatErrors: format.filter(isError).length,
    formatWarnings: format.filter((f) => !isError(f)).length,
    content,
    needsReview: content.filter((f) => f.needs_teacher_review).length,
  };
};
