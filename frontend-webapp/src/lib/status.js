// Состояние работы словами — одно место для списков, экрана работы и кабинета преподавателя.
// tone — иконка и цвет (components/Icon.jsx, StatusIcon): ok, error, warning, pending, muted.
import { count } from './format';
import { summarizeFindings } from './findings';

// Причины сбоя (queue-v2.md, раздел 5) — студенту словами, без кодов.
const FAILURE_TEXT = {
  FILE_NOT_FOUND: 'Файл работы не найден.',
  PDF_UNREADABLE: 'Файл не открывается: он повреждён или это не PDF.',
  PDF_ENCRYPTED: 'Файл защищён паролем.',
  TOO_LARGE: 'В работе слишком много страниц.',
  TIMEOUT: 'Проверка не уложилась по времени.',
  STUCK: 'Проверка зависла и остановлена.',
  INTERNAL: 'Сбой проверки.',
};

const remarks = (n) => count(n, 'замечание', 'замечания', 'замечаний');

// Оформление уже проверено: работа прошла его и ждёт содержания или проверка закончена.
const formatChecked = (s) => s.status === 'SUCCESS' || s.status === 'REJECTED'
  || (s.status === 'PROCESSING' && s.content_status === 'PENDING');

export const isInProgress = (s) => s.status === 'QUEUED' || s.status === 'PROCESSING' || s.content_status === 'PENDING';

/** Состояние работы студента одной строкой — для списка. */
export function workStatus(s) {
  if (s.teacher_verdict === 'APPROVED') return { tone: 'ok', label: 'Принята' };
  if (s.teacher_verdict === 'REVISION') return { tone: 'warning', label: 'На доработке' };
  if (s.status === 'FAILED') return { tone: 'error', label: 'Не удалось проверить' };
  if (s.status === 'QUEUED') return { tone: 'pending', label: 'В очереди' };
  if (s.status === 'PROCESSING') return { tone: 'pending', label: formatChecked(s) ? 'Проверяется содержание' : 'Проверяется' };
  if (s.status === 'REJECTED') {
    const n = summarizeFindings(s.format_errors).formatErrors;
    return { tone: 'error', label: n ? `Ошибки в оформлении: ${n}` : 'Ошибки в оформлении' };
  }
  if (s.status === 'SUCCESS') return { tone: 'ok', label: 'У преподавателя' };
  return { tone: 'muted', label: s.status || '' };
}

/** Оформление: строка для экрана работы. */
export function formatStep(s) {
  if (s.status === 'FAILED') {
    return { tone: 'error', text: FAILURE_TEXT[s.failure_code] || 'Не удалось проверить.' };
  }
  if (!formatChecked(s)) return { tone: 'pending', text: s.status === 'QUEUED' ? 'В очереди' : 'Проверяется' };
  const { formatErrors, formatWarnings } = summarizeFindings(s.format_errors);
  const warnings = formatWarnings ? `, предупреждений: ${formatWarnings}` : '';
  return s.status === 'REJECTED'
    ? { tone: 'error', text: `Ошибок: ${formatErrors || 1}${warnings}` }
    : { tone: 'ok', text: `Без ошибок${warnings}` };
}

/** Содержание: строка для экрана работы и карточки преподавателя. */
export function contentStep(s) {
  if (s.status === 'FAILED' || s.content_status === 'NONE') return { tone: 'muted', text: 'Не проверялось' };
  if (!formatChecked(s)) return { tone: 'muted', text: 'После оформления' };
  if (s.content_status === 'PENDING') return { tone: 'pending', text: 'Проверяется' };
  if (s.content_status === 'TIMEOUT') return { tone: 'warning', text: 'Не проверено: не хватило времени' };
  if (s.content_status === 'FAILED') return { tone: 'warning', text: 'Не проверено: сбой проверки' };
  const { content, needsReview } = summarizeFindings(s.format_errors);
  if (!content.length) return { tone: 'ok', text: 'Без замечаний' };
  const review = needsReview ? `, на решение преподавателя: ${needsReview}` : '';
  return { tone: 'warning', text: `${remarks(content.length)}${review}` };
}

/** Преподаватель: строка для экрана работы студента. */
export function teacherStep(s) {
  if (s.teacher_verdict === 'APPROVED') return { tone: 'ok', text: 'Принял работу' };
  if (s.teacher_verdict === 'REVISION') return { tone: 'warning', text: 'Вернул на доработку' };
  if (s.status === 'SUCCESS' || s.status === 'REJECTED') return { tone: 'pending', text: 'Ждёт решения' };
  if (s.status === 'FAILED') return { tone: 'muted', text: 'Работа не отправлена' };
  return { tone: 'muted', text: 'Получит работу после проверки' };
}

/** Итог автоматической проверки для преподавателя: оформление и содержание. */
export function autoCheckForTeacher(s) {
  const { formatErrors, content, needsReview } = summarizeFindings(s.format_errors);
  const format = s.status === 'REJECTED'
    ? { tone: 'error', text: `Ошибок оформления: ${formatErrors || 1}` }
    : { tone: 'ok', text: 'Оформление без ошибок' };
  let contentLine;
  if (s.content_status === 'PENDING') contentLine = { tone: 'pending', text: 'Содержание проверяется' };
  else if (s.content_status === 'TIMEOUT') contentLine = { tone: 'warning', text: 'Содержание не проверено: не хватило времени' };
  else if (s.content_status === 'FAILED') contentLine = { tone: 'warning', text: 'Содержание не проверено: сбой' };
  else if (content.length) {
    contentLine = { tone: 'warning', text: `Содержание: ${remarks(content.length)}${needsReview ? `, на ваше решение: ${needsReview}` : ''}` };
  } else contentLine = null;
  return { format, content: contentLine };
}
