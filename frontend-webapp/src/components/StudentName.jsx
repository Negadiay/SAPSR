import { studentLabel } from '../lib/names';

// ФИО студента для преподавателя. У тёзки — постоянный номер в скобках и значок с подписью (решение 4):
// цветом не помечаем, Telegram ID не показываем.
export default function StudentName({ submission }) {
  const namesake = Boolean(submission.student?.has_namesake);
  return (
    <>
      {studentLabel(submission)}
      {namesake && <span className="namesake-badge" title="В группе есть студент с таким же ФИО">тёзка</span>}
    </>
  );
}