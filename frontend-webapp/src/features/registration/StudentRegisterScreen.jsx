import { useState } from 'react';
import NameFields from '../../components/NameFields';
import Screen from '../../components/Screen';
import PrimaryAction from '../../components/PrimaryAction';
import { useBackButton } from '../../telegram/useTelegramButtons';
import { groupProblem, studentFormProblem } from './useRegistration';

// Регистрация студента по полям (решение 4): фамилия, имя, отчество и номер группы.
export default function StudentRegisterScreen({ registration }) {
  const { student, changeStudent, regError, registering, submitStudent, backToRole } = registration;
  const nativeBack = useBackButton(backToRole);
  // Ошибка формата — когда пользователь ушёл из поля, а не на каждой цифре.
  const [groupTouched, setGroupTouched] = useState(false);
  const groupError = groupTouched ? groupProblem(student.group) : '';

  return (
    <Screen>
      <h2 className="screen-lead">Регистрация студента</h2>
      <form onSubmit={submitStudent} className="form">
        <NameFields value={student} onChange={changeStudent} idPrefix="student" />
        <div className="field">
          <label className="field-label" htmlFor="student-group">Номер группы</label>
          <input id="student-group" type="text" className="input" inputMode="numeric" pattern="\d{6}" maxLength={6}
            autoComplete="off" placeholder="321702" value={student.group}
            onChange={(e) => changeStudent({ ...student, group: e.target.value })}
            onBlur={() => setGroupTouched(true)}
            aria-invalid={Boolean(groupError)} aria-describedby="student-group-hint" />
          <p id="student-group-hint" className={groupError ? 'field-error' : 'hint'}>
            {groupError || 'Шесть цифр. По группе определяются курс и семестр; сменить её потом сможет только администратор.'}
          </p>
        </div>
        {regError && <p className="field-error" role="alert">{regError}</p>}
        <PrimaryAction text="Зарегистрироваться" progressText="Регистрация…" submit onClick={submitStudent}
          disabled={Boolean(studentFormProblem(student))} progress={registering} />
        {!nativeBack && <button type="button" className="btn btn-secondary btn-block" onClick={backToRole}>Назад</button>}
      </form>
    </Screen>
  );
}