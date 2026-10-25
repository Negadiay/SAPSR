import { formatDay } from '../../lib/format';

const STAGE_DATIVE = { 1: '1-й', 2: '2-й', 3: '3-й' };

// Курс, семестр, этап и срок студент не выбирает: их определяет сервер по группе и учебному календарю
// (решение 11), а ошибки исправляет администратор. От них зависят требования методички.
export default function TermInfo({ me }) {
  if (!me || me.role !== 'STUDENT') return null;
  if (!me.semester) {
    return (
      <p className="term-info term-info-warning">
        Семестр группы {me.group || '—'} не определён: требования семестра не проверятся. Сообщите администратору.
      </p>
    );
  }
  return (
    <p className="term-info">
      <span>{me.course} курс · {me.semester} семестр</span>
      <span>
        {me.stage ? `к ${STAGE_DATIVE[me.stage]} опроцентовке` : 'итоговая сдача'}
        {me.deadline && ` · сдача до ${formatDay(me.deadline, { short: true })}`}
      </span>
    </p>
  );
}
