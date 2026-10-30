import { useState } from 'react';
import Screen from '../../components/Screen';
import PrimaryAction from '../../components/PrimaryAction';
import { useBackButton } from '../../telegram/useTelegramButtons';
import { studentInputProblem } from './useRegistration';

export default function StudentRegisterScreen({ registration }) {
  const { regInput, changeRegInput, regError, registering, submitStudent, backToRole } = registration;
  const nativeBack = useBackButton(backToRole);
  // Ошибка формата — когда пользователь ушёл из поля, а не на каждой букве.
  const [touched, setTouched] = useState(false);
  const problem = studentInputProblem(regInput);

  return (
    <Screen>
      <h2 className="screen-lead">Регистрация студента</h2>
      <form onSubmit={submitStudent} className="form">
        <div className="field">
          <label className="field-label" htmlFor="student-name">ФИО и группа</label>
          <input id="student-name" type="text" className="input" placeholder="Иванов И.И., 321702" autoComplete="off"
            value={regInput} onChange={(e) => changeRegInput(e.target.value)} onBlur={() => setTouched(true)}
            aria-invalid={touched && Boolean(problem)} aria-describedby="student-name-hint" />
          <p id="student-name-hint" className={touched && problem ? 'field-error' : 'hint'}>
            {touched && problem ? problem : 'Фамилия и инициалы, затем номер группы через запятую.'}
          </p>
        </div>
        {regError && <p className="field-error" role="alert">{regError}</p>}
        <PrimaryAction text="Зарегистрироваться" progressText="Регистрация…" submit onClick={submitStudent}
          disabled={Boolean(problem)} progress={registering} />
        {!nativeBack && <button type="button" className="btn btn-secondary btn-block" onClick={backToRole}>Назад</button>}
      </form>
    </Screen>
  );
}
