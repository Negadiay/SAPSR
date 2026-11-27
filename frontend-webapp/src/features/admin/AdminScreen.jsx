import { useState } from 'react';
import AppHeader from '../../components/AppHeader';
import Icon from '../../components/Icon';
import StatusLine from '../../components/StatusLine';
import ConfirmDialog from '../../components/ConfirmDialog';
import { formatDay } from '../../lib/format';
import RedZone from './RedZone';
import { useAdminSystem } from './useAdminSystem';
import { panelMotion, tabDirection, useSwipeTabs } from '../../navigation/useSwipeTabs';
import './admin.css';

// Экран администратора (решение 11): семестр, сроки по курсам, группы с людьми — всё, что сервер определяет сам,
// исправляется здесь, без правки кода и без похода в базу. Подписи — минимальные: экран рабочий.

const HALF_TITLES = { AUTUMN: 'осенний', SPRING: 'весенний' };
const DATE_FIELDS = [['1', '1-я опроцентовка'], ['2', '2-я опроцентовка'], ['3', '3-я опроцентовка'], ['deadline', 'Сдача']];
const STAGE_TITLES = { 1: '1-я опроцентовка', 2: '2-я опроцентовка', 3: '3-я опроцентовка' };
const SECTIONS_KEY = 'sapsr_admin_sections';
const TAB_KEY = 'sapsr_admin_tab';
const TABS = [['study', 'Учёба'], ['system', 'Красная зона']];
const TAB_IDS = TABS.map(([id]) => id);

const semesterOf = (course, half) => (half === 'AUTUMN' ? 2 * course - 1 : 2 * course);
const range = (n) => Array.from({ length: n }, (_, i) => i + 1);
const digitsText = (digits) => digits.slice(0, -1).join(', ') + ' или ' + digits[digits.length - 1];

// Какие разделы свёрнуты — запоминается на этом устройстве. Без хранилища разделы открываются как по умолчанию.
const readSections = () => {
  try {
    return JSON.parse(localStorage.getItem(SECTIONS_KEY)) || {};
  } catch {
    return {};
  }
};

const readTab = () => {
  try {
    return localStorage.getItem(TAB_KEY) === 'system' ? 'system' : 'study';
  } catch {
    return 'study';
  }
};

const saveTab = (tab) => {
  try {
    localStorage.setItem(TAB_KEY, tab);
  } catch {
    // не запомнится — не страшно
  }
};

const saveSection = (id, open) => {
  try {
    localStorage.setItem(SECTIONS_KEY, JSON.stringify({ ...readSections(), [id]: open }));
  } catch {
    // не запомнится — не страшно
  }
};

// Раздел, который сворачивается по нажатию на заголовок; summary виден в свёрнутом.
function AdminSection({ id, title, summary, defaultOpen = true, children }) {
  const [open, setOpen] = useState(() => readSections()[id] ?? defaultOpen);
  const bodyId = `admin-section-${id}`;
  return (
    <section className="card admin-section" data-tour={`admin-${id}`}>
      <button type="button" className="admin-section-head" aria-expanded={open} aria-controls={open ? bodyId : undefined}
        onClick={() => { saveSection(id, !open); setOpen(!open); }}>
        <span className="settings-label">{title}</span>
        {!open && summary && <span className="admin-section-summary">{summary}</span>}
        <Icon name={open ? 'chevronUp' : 'chevronDown'} className="admin-section-chevron" />
      </button>
      {open && <div id={bodyId} className="admin-section-body">{children}</div>}
    </section>
  );
}

// Номер группы по ходу ввода: только цифры, не больше шести, первая — из возможных в этом учебном году.
// null — такую цифру первой ввести нельзя.
const acceptGroup = (value, digits) => {
  const v = value.replace(/\D/g, '').slice(0, 6);
  return v && !digits.includes(Number(v[0])) ? null : v;
};

function GroupInput({ id, label, value, digits, onChange }) {
  const [rejected, setRejected] = useState(false);
  return (
    <>
      <input id={id} aria-label={label} className="input" inputMode="numeric" maxLength={6}
        placeholder={`${digits[0]}…`} value={value}
        onChange={(e) => {
          const next = acceptGroup(e.target.value, digits);
          setRejected(next === null);
          if (next !== null) onChange(next);
        }} />
      {rejected && <p className="field-error">Первая цифра — {digitsText(digits)}</p>}
    </>
  );
}

function HalfSection({ calendar, saving, onChange }) {
  const value = calendar.half_overridden ? calendar.half : 'AUTO';
  const summary = HALF_TITLES[calendar.half] + (calendar.half_overridden ? ', вручную' : '');
  return (
    <AdminSection id="half" title={`Учебный год ${calendar.academic_year_title}`} summary={summary}>
      <div className="admin-row">
        <label htmlFor="admin-half">Семестр</label>
        <select id="admin-half" className="input" value={value} disabled={saving}
          onChange={(e) => onChange(e.target.value)}>
          <option value="AUTO">По дате ({HALF_TITLES[calendar.half_by_date]})</option>
          <option value="AUTUMN">Осенний</option>
          <option value="SPRING">Весенний</option>
        </select>
      </div>
      {calendar.half_overridden && <p className="hint">Задан вручную</p>}
    </AdminSection>
  );
}

// key — курс и даты с сервера: после сохранения или смены курса поля заполняются заново.
function CourseDates({ row, window, saving, onSave, onSaveAll }) {
  const [dates, setDates] = useState(() => Object.fromEntries(DATE_FIELDS.map(([k]) => [k, row.dates[k] || ''])));
  const changed = DATE_FIELDS.some(([k]) => (row.dates[k] || '') !== dates[k]);
  const payload = () => Object.fromEntries(Object.entries(dates).map(([k, v]) => [k, v || null]));
  const current = row.current_stage;
  return (
    <>
      {DATE_FIELDS.map(([key, label]) => (
        <div key={key} className="admin-date-row">
          <label htmlFor={`admin-date-${key}`}>{label}</label>
          <input id={`admin-date-${key}`} type="date" className="input admin-date"
            min={window.from} max={window.to} value={dates[key]}
            onChange={(e) => setDates({ ...dates, [key]: e.target.value })} />
        </div>
      ))}
      {current && (
        <p className="hint">Сейчас: {STAGE_TITLES[current]}, {formatDay(row.dates[String(current)], { short: true })}</p>
      )}
      <div className="actions">
        <button type="button" className="btn btn-primary" disabled={!changed || saving} onClick={() => onSave(payload())}>
          Сохранить
        </button>
        <button type="button" className="btn btn-secondary" disabled={saving} onClick={() => onSaveAll(payload())}>
          Для всех курсов
        </button>
      </div>
    </>
  );
}

function ScheduleSection({ calendar, saving, onChange }) {
  const [course, setCourse] = useState(1);
  const [allDates, setAllDates] = useState(null);
  const row = calendar.schedule.find((r) => r.course === course) || calendar.schedule[0];
  return (
    <AdminSection id="schedule" title={`Сроки · ${HALF_TITLES[calendar.half]} семестр`}>
      <div className="segmented" role="tablist" aria-label="Сроки курса">
        {calendar.schedule.map((r) => (
          <button key={r.course} type="button" role="tab" aria-selected={r.course === row.course}
            className={`segment ${r.course === row.course ? 'segment-active' : ''}`}
            onClick={() => setCourse(r.course)}>
            {r.course} курс
          </button>
        ))}
      </div>
      <CourseDates key={`${row.course}-${JSON.stringify(row.dates)}`} row={row} window={calendar.date_window}
        saving={saving} onSave={(dates) => onChange([row.course], dates)} onSaveAll={setAllDates} />
      {allDates && (
        <ConfirmDialog title="Сроки для всех курсов?" body="Сроки остальных курсов заменятся этими."
          confirmLabel="Заменить"
          onConfirm={() => { onChange(calendar.schedule.map((r) => r.course), allDates); setAllDates(null); }}
          onCancel={() => setAllDates(null)} />
      )}
    </AdminSection>
  );
}

function GroupChip({ group, selected, onSelect }) {
  return (
    <button type="button" aria-pressed={selected}
      className={`chip ${group.course_overridden ? 'chip-manual' : ''} ${selected ? 'chip-active' : ''}`}
      title={`Студентов: ${group.students}, преподавателей: ${group.teachers}`}
      onClick={() => onSelect(selected ? null : group.group)}>
      {group.group}
      {group.course_overridden && <Icon name="edit" label="курс задан вручную" className="chip-mark" />}
    </button>
  );
}

// Правка студента. «Сделать администратором» и «Удалить пользователя» — только отсюда: в списке их не задеть случайно при листании.
function PersonForm({ person, digits, saving, onSave, onCancel, onAppoint, onDelete }) {
  const [name, setName] = useState(person.name);
  const [group, setGroup] = useState(person.group || '');
  const valid = name.trim().length > 1 && /^\d{6}$/.test(group);
  return (
    <li className="admin-person admin-person-form">
      <input aria-label="ФИО" className="input" value={name} onChange={(e) => setName(e.target.value)} />
      <GroupInput id={`admin-person-group-${person.id}`} label="Группа" value={group} digits={digits} onChange={setGroup} />
      <div className="actions">
        <button type="button" className="btn btn-primary" disabled={!valid || saving} onClick={() => onSave({ name, group })}>
          Сохранить
        </button>
        <button type="button" className="btn btn-secondary" onClick={onCancel}>Отмена</button>
      </div>
      <button type="button" className="link-btn link-danger" disabled={saving} onClick={onAppoint}>
        <Icon name="admin" /><span>Сделать администратором</span>
      </button>
      <button type="button" className="link-btn link-danger" disabled={saving} onClick={onDelete}>
        <Icon name="trash" /><span>Удалить пользователя</span>
      </button>
    </li>
  );
}

// Студентов можно исправить и удалить из правки; преподавателей — только посмотреть или удалить.
function PeopleList({ title, people, editable, digits, saving, onSave, onAppoint, onDelete }) {
  const [editing, setEditing] = useState(null);
  if (!people) return <p className="hint">Загрузка…</p>;
  return (
    <div className="admin-people">
      <p className="admin-people-title">{title}{people.length > 0 && ` · ${people.length}`}</p>
      {people.length === 0 && <p className="hint">Нет</p>}
      <ul>
        {people.map((p) => (editing === p.id ? (
          <PersonForm key={p.id} person={p} digits={digits} saving={saving}
            onSave={async (data) => { if (await onSave(p.id, data)) setEditing(null); }}
            onCancel={() => setEditing(null)} onAppoint={() => onAppoint(p)} onDelete={() => onDelete(p)} />
        ) : (
          <li key={p.id} className="admin-person">
            <span className="admin-person-name">{p.name}</span>
            {editable ? (
              <button type="button" className="icon-btn" onClick={() => setEditing(p.id)}>
                <Icon name="edit" label={`Изменить: ${p.name}`} />
              </button>
            ) : onDelete && (
              <button type="button" className="icon-btn icon-btn-danger" disabled={saving} onClick={() => onDelete(p)}>
                <Icon name="trash" label={`Удалить: ${p.name}`} />
              </button>
            )}
          </li>
        )))}
      </ul>
    </div>
  );
}

function GroupPanel({ group, calendar, admin, onClose, onAppoint, onDeleteUser, onRemove }) {
  const { people, saving, changeCourse, changeUser } = admin;
  const possible = calendar.group_digits.includes(Number(group.group[0]));
  const loaded = people?.group === group.group;
  return (
    <div className="admin-panel">
      <div className="admin-panel-head">
        <span className="admin-panel-title">Группа {group.group}</span>
        <button type="button" className="icon-btn" onClick={onClose}><Icon name="close" label="Закрыть" /></button>
      </div>
      {possible ? (
        <div className="admin-row">
          <label htmlFor="admin-group-course">Курс</label>
          <select id="admin-group-course" className="input" disabled={saving}
            value={group.course_overridden ? String(group.course) : ''}
            onChange={(e) => changeCourse(group.group, e.target.value ? Number(e.target.value) : null)}>
            {/* У добавленной вручную группы курс есть только записью администратора — «по номеру» её бы убрало */}
            {!group.manual && <option value="">По номеру ({group.course_by_number})</option>}
            {range(calendar.max_course).map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
      ) : (
        <p className="hint">Группа не относится к текущим курсам</p>
      )}
      <PeopleList title="Студенты" people={loaded ? people.students : null} editable digits={calendar.group_digits}
        saving={saving} onSave={changeUser} onAppoint={onAppoint}
        onDelete={(p) => onDeleteUser({ ...p, role: 'STUDENT' })} />
      <PeopleList title="Преподаватели" people={loaded ? people.teachers : null} saving={saving}
        onDelete={(p) => onDeleteUser({ ...p, role: 'TEACHER' })} />
      {group.manual && (
        <button type="button" className="btn btn-ghost-danger btn-block" disabled={saving}
          onClick={() => onRemove(group.group)}>
          <Icon name="trash" /><span>Удалить группу</span>
        </button>
      )}
    </div>
  );
}

function GroupsSection({ calendar, admin }) {
  const [selected, setSelected] = useState(null);
  const [appointee, setAppointee] = useState(null);
  const [deletingUser, setDeletingUser] = useState(null);
  const [removing, setRemoving] = useState(null);
  const courses = range(calendar.max_course).reverse();
  const unknown = calendar.groups.filter((g) => g.course == null);
  const selectedGroup = calendar.groups.find((g) => g.group === selected);

  const select = (group) => { setSelected(group); admin.openGroup(group); };
  const row = (title, groups) => (
    <div className="admin-course" key={title}>
      <p className="admin-course-title">{title}</p>
      <div className="chips">
        {groups.map((g) => <GroupChip key={g.group} group={g} selected={g.group === selected} onSelect={select} />)}
      </div>
    </div>
  );

  return (
    <AdminSection id="groups" title="Группы">
      {calendar.groups.length === 0 && <p className="hint">Пока нет</p>}
      {courses.map((c) => {
        const groups = calendar.groups.filter((g) => g.course === c);
        return groups.length > 0 ? row(`${c} курс · ${semesterOf(c, calendar.half)} семестр`, groups) : null;
      })}
      {unknown.length > 0 && row('Курс не определён', unknown)}
      {selectedGroup && (
        <GroupPanel key={selectedGroup.group} group={selectedGroup} calendar={calendar} admin={admin}
          onClose={() => select(null)} onAppoint={setAppointee} onDeleteUser={setDeletingUser} onRemove={setRemoving} />
      )}
      {appointee && (
        <ConfirmDialog title="Назначить администратором?" danger
          body={`«${appointee.name}» станет администратором. Работы и учебные данные этого аккаунта удалятся безвозвратно.`}
          confirmLabel="Назначить"
          onConfirm={() => { admin.appointAdmin(appointee.id); setAppointee(null); }}
          onCancel={() => setAppointee(null)} />
      )}
      {deletingUser && (
        <ConfirmDialog title="Удалить пользователя?" danger
          body={deletingUser.role === 'TEACHER'
            ? `«${deletingUser.name}» будет удалён из системы, а у его работ сбросится преподаватель. Пользователь сможет зарегистрироваться заново.`
            : `«${deletingUser.name}» и все его работы будут удалены безвозвратно. Пользователь сможет зарегистрироваться заново.`}
          confirmLabel="Удалить"
          onConfirm={() => { admin.removeUser(deletingUser.id); setDeletingUser(null); }}
          onCancel={() => setDeletingUser(null)} />
      )}
      {removing && (
        <ConfirmDialog title={`Удалить группу ${removing}?`} body="В группе никого нет." danger
          confirmLabel="Удалить"
          onConfirm={() => { admin.removeGroup(removing); setRemoving(null); select(null); }}
          onCancel={() => setRemoving(null)} />
      )}
    </AdminSection>
  );
}

// Две вкладки: «Учёба» — календарь и группы; «Красная зона» — то, что меняет работу всей системы (решение 13).
// Между вкладками на телефоне можно листать свайпом.
export default function AdminScreen({ admin, header }) {
  const { calendar, error, saving, changeHalf, changeSchedule } = admin;
  const [tab, setTab] = useState(readTab);
  const [direction, setDirection] = useState(0);
  const redZone = useAdminSystem({ active: tab === 'system' });
  const open = (id) => {
    if (id === tab) return;
    setDirection(tabDirection(TAB_IDS, tab, id));
    saveTab(id);
    setTab(id);
  };
  const swipe = useSwipeTabs({ ids: TAB_IDS, current: tab, onChange: open });
  return (
    <div className="screen-body" ref={swipe}>
      <AppHeader title="Управление" {...header} />
      <div className="segmented admin-tabs" role="tablist" aria-label="Разделы управления">
        {TABS.map(([id, title]) => (
          <button key={id} type="button" role="tab" aria-selected={tab === id} data-tour={`admin-tab-${id}`}
            className={`segment ${tab === id ? 'segment-active' : ''} ${id === 'system' ? 'segment-danger' : ''}`}
            onClick={() => open(id)}>
            {id === 'system' && <Icon name="warning" />}{title}
          </button>
        ))}
      </div>
      <div key={tab} className={`tab-panel ${panelMotion(direction)}`}>
        {tab === 'system' && <RedZone redZone={redZone} />}
        {tab === 'study' && (
          <>
            {error && <StatusLine tone="error" className="form-status" role="alert">{error}</StatusLine>}
            {!calendar && !error && <p className="empty">Загрузка…</p>}
            {calendar && (
              <>
                <HalfSection calendar={calendar} saving={saving} onChange={changeHalf} />
                <ScheduleSection calendar={calendar} saving={saving} onChange={changeSchedule} />
                <GroupsSection calendar={calendar} admin={admin} />
              </>
            )}
          </>
        )}
      </div>
    </div>
  );
}
