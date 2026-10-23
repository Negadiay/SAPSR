import { useState } from 'react';
import './admin.css';

// Экран администратора (решение 11): то, что приложение определяет само — половина года, даты опроцентовок,
// курсы групп, — видно строками и исправляется здесь же, без правки кода.

const HALF_TITLES = { AUTUMN: 'осенний', SPRING: 'весенний' };
const STAGE_TITLES = { 1: '1-я', 2: '2-я', 3: '3-я' };
const STAGE_DATIVE = { 1: '1-й', 2: '2-й', 3: '3-й' };

const semesterOf = (course, half) => (half === 'AUTUMN' ? 2 * course - 1 : 2 * course);
const formatDay = (iso) => (iso ? iso.split('-').reverse().join('.') : '');

function HalfSection({ calendar, saving, onChange }) {
  const value = calendar.half_overridden ? calendar.half : 'AUTO';
  return (
    <section className="settings-section admin-section">
      <div className="settings-label">Учебный год {calendar.academic_year_title}</div>
      <label className="select-label" htmlFor="admin-half">Семестр сейчас</label>
      <select id="admin-half" className="teacher-select" value={value} disabled={saving}
        onChange={(e) => onChange(e.target.value)}>
        <option value="AUTO">По дате — {HALF_TITLES[calendar.half_by_date]}</option>
        <option value="AUTUMN">Осенний (нечётный семестр)</option>
        <option value="SPRING">Весенний (чётный семестр)</option>
      </select>
      <p className="reg-hint admin-hint">
        {calendar.half_overridden
          ? `Задано вручную, по дате сейчас ${HALF_TITLES[calendar.half_by_date]}. Когда дата догонит, исправление снимется само.`
          : 'С 1 августа по 15 января — осенний, с 16 января — весенний.'}
      </p>
    </section>
  );
}

function stageHint(calendar) {
  const current = calendar.current_stage;
  if (current) {
    const date = calendar.procentovki.find((p) => p.stage === current)?.date;
    return `Сейчас работы проверяются к ${STAGE_DATIVE[current]} опроцентовке (${formatDay(date)}).`;
  }
  if (calendar.procentovki.some((p) => p.date)) {
    return 'Опроцентовки прошли — итоговая сдача: проверяются все требования семестра.';
  }
  return 'Даты не заданы: этап не определяется, проверяются все требования семестра.';
}

// key у компонента — даты с сервера: после сохранения поля заполняются заново.
function ProcentovkiSection({ calendar, saving, onSave }) {
  const fromServer = Object.fromEntries(calendar.procentovki.map((p) => [p.stage, p.date || '']));
  const [dates, setDates] = useState(fromServer);
  const changed = calendar.procentovki.some((p) => (p.date || '') !== dates[p.stage]);

  const save = () => onSave(Object.fromEntries(Object.entries(dates).map(([stage, date]) => [stage, date || null])));

  return (
    <section className="settings-section admin-section">
      <div className="settings-label">Опроцентовки · {HALF_TITLES[calendar.half]} семестр</div>
      {calendar.procentovki.map((p) => (
        <div key={p.stage} className="admin-date-row">
          <label className="settings-desc" htmlFor={`admin-stage-${p.stage}`}>{STAGE_TITLES[p.stage]} опроцентовка</label>
          <input id={`admin-stage-${p.stage}`} type="date" className="teacher-select admin-date"
            value={dates[p.stage]} onChange={(e) => setDates({ ...dates, [p.stage]: e.target.value })} />
        </div>
      ))}
      <p className="reg-hint admin-hint">{stageHint(calendar)}</p>
      <button type="button" className="submit-btn admin-save" disabled={!changed || saving} onClick={save}>
        Сохранить даты
      </button>
    </section>
  );
}

function GroupChip({ group, selected, onSelect }) {
  const people = `студентов: ${group.students}, преподавателей: ${group.teachers}`;
  return (
    <button type="button" aria-pressed={selected}
      className={`admin-chip ${group.course_overridden ? 'admin-chip-manual' : ''} ${selected ? 'admin-chip-selected' : ''}`}
      title={group.course_overridden ? `${people}; курс задан вручную` : people}
      onClick={() => onSelect(selected ? null : group.group)}>
      {group.group}{group.course_overridden && <span aria-label="курс задан вручную"> ✎</span>}
    </button>
  );
}

// key — номер группы: при выборе другой группы поле курса заполняется заново.
function GroupEditor({ group, maxCourse, saving, onSave }) {
  const [course, setCourse] = useState(group.course_overridden ? String(group.course) : '');
  const byNumber = group.course_by_number ? `${group.course_by_number} курс` : 'не определяется';
  return (
    <div className="admin-editor">
      <div className="admin-editor-title">Группа {group.group}</div>
      <div className="settings-desc">По номеру — {byNumber}. Студентов: {group.students}, преподавателей: {group.teachers}.</div>
      <label className="select-label" htmlFor="admin-group-course">Курс в этом учебном году</label>
      <select id="admin-group-course" className="teacher-select" value={course} onChange={(e) => setCourse(e.target.value)}>
        <option value="">По номеру группы{group.course_by_number ? ` (${group.course_by_number})` : ''}</option>
        {Array.from({ length: maxCourse }, (_, i) => i + 1).map((n) => (
          <option key={n} value={n}>{n} курс</option>
        ))}
      </select>
      <button type="button" className="submit-btn admin-save" disabled={saving}
        onClick={() => onSave(group.group, course ? Number(course) : null)}>
        Сохранить курс
      </button>
    </div>
  );
}

function AddGroup({ maxCourse, saving, onSave }) {
  const [number, setNumber] = useState('');
  const [course, setCourse] = useState('');
  const valid = /^\d{6}$/.test(number) && course;
  const save = () => { onSave(number, Number(course)); setNumber(''); setCourse(''); };
  return (
    <div className="admin-add">
      <div className="select-label">Группы нет в списке — задать курс по номеру</div>
      <div className="admin-add-row">
        <input aria-label="Номер группы" className="teacher-select" inputMode="numeric" maxLength={6}
          placeholder="321702" value={number} onChange={(e) => setNumber(e.target.value.replace(/\D/g, ''))} />
        <select aria-label="Курс новой группы" className="teacher-select" value={course}
          onChange={(e) => setCourse(e.target.value)}>
          <option value="">Курс</option>
          {Array.from({ length: maxCourse }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}
        </select>
      </div>
      <button type="button" className="secondary-btn admin-save" disabled={!valid || saving} onClick={save}>
        Задать курс
      </button>
    </div>
  );
}

function GroupsSection({ calendar, saving, onChange }) {
  const [selected, setSelected] = useState(null);
  const maxCourse = calendar.max_course;
  const courses = Array.from({ length: maxCourse }, (_, i) => maxCourse - i);
  const unknown = calendar.groups.filter((g) => g.course == null);
  const selectedGroup = calendar.groups.find((g) => g.group === selected);

  const row = (title, groups) => (
    <div className="admin-course" key={title}>
      <div className="admin-course-title">{title}</div>
      <div className="admin-chips">
        {groups.map((g) => (
          <GroupChip key={g.group} group={g} selected={g.group === selected} onSelect={setSelected} />
        ))}
      </div>
    </div>
  );

  return (
    <section className="settings-section admin-section">
      <div className="settings-label">Курсы групп</div>
      <p className="reg-hint admin-hint">
        Курс — по первой цифре номера: это год поступления. Группы студентов и преподавателей приложения;
        ✎ — курс задан вручную. Нажмите на группу, чтобы исправить.
      </p>
      {calendar.groups.length === 0 && <p className="settings-desc">Групп пока нет: никто не зарегистрирован.</p>}
      {courses.map((c) => {
        const groups = calendar.groups.filter((g) => g.course === c);
        return groups.length > 0 ? row(`${c} курс · ${semesterOf(c, calendar.half)} семестр`, groups) : null;
      })}
      {unknown.length > 0 && row('Курс не определён', unknown)}
      {selectedGroup && (
        <GroupEditor key={selectedGroup.group} group={selectedGroup} maxCourse={maxCourse} saving={saving}
          onSave={(group, course) => { onChange(group, course); setSelected(null); }} />
      )}
      <AddGroup maxCourse={maxCourse} saving={saving} onSave={onChange} />
    </section>
  );
}

export default function AdminTab({ admin }) {
  const { calendar, error, saving, changeHalf, changeProcentovki, changeCourse } = admin;
  return (
    <div className="tab-view">
      <h2 className="view-title">Управление</h2>
      <p className="reg-hint admin-intro">
        Здесь — то, что приложение определяет само. Если что-то определилось неверно, исправьте: новые загрузки
        сразу проверяются по исправленному.
      </p>
      {error && <div className="status-msg admin-error" role="alert">❌ {error}</div>}
      {!calendar && !error && <p className="description">Загрузка...</p>}
      {calendar && (
        <>
          <HalfSection calendar={calendar} saving={saving} onChange={changeHalf} />
          <ProcentovkiSection key={JSON.stringify(calendar.procentovki)} calendar={calendar} saving={saving}
            onSave={changeProcentovki} />
          <GroupsSection calendar={calendar} saving={saving} onChange={changeCourse} />
        </>
      )}
    </div>
  );
}
