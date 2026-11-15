// «Фамилия И. О.» из данных GET /me. У аккаунтов старой формы («Иванов И.И. (гр. 321702)») имени
// и отчества в базе нет — инициалы берутся из full_name.
const initial = (name) => (name ? `${name.trim()[0].toUpperCase()}.` : '');

const lastNameOf = (me) => (me ? me.last_name || (me.full_name || '').trim().split(/\s+/)[0] || '' : '');

export const shortName = (me) => {
  if (!me) return '';
  const fullName = (me.full_name || '').trim();
  const lastName = lastNameOf(me);
  if (!lastName) return '';

  let initials = [initial(me.first_name), initial(me.middle_name)].filter(Boolean);
  if (initials.length === 0) {
    const fromFullName = fullName.slice(lastName.length).match(/^\s*([А-ЯЁA-Z])\.\s*(?:([А-ЯЁA-Z])\.)?/);
    if (fromFullName) initials = [fromFullName[1], fromFullName[2]].filter(Boolean).map((c) => `${c}.`);
    else initials = fullName.split(/\s+/).slice(1, 3).filter((p) => !p.startsWith('(')).map(initial);
  }
  return [lastName, ...initials].join(' ');
};

// Часть имени по правилам сервера (api-v1.md, раздел 4): буквы кириллицы, дефис и пробел, до 100 символов.
const NAME_PART = /^[А-ЯЁа-яё]+(?:\s*-\s*[А-ЯЁа-яё]+|\s+[А-ЯЁа-яё]+)*$/;

// Что не так в поле ФИО; пусто — всё верно. label — «Фамилия», «Имя», «Отчество».
export const namePartProblem = (value, label) => {
  const text = (value || '').trim();
  if (!text) return `${label}: заполните поле.`;
  if (text.length > 100) return `${label}: не длиннее 100 букв.`;
  return NAME_PART.test(text) ? '' : `${label}: только русские буквы, дефис и пробел.`;
};

// Поля формы ФИО из данных GET /me и обратно — в тело запроса регистрации и PATCH /me.
export const nameFormOf = (me) => ({
  lastName: me?.last_name || '', firstName: me?.first_name || '', middleName: me?.middle_name || '',
  noMiddle: Boolean(me?.first_name) && !me?.middle_name,
});

export const nameFormProblem = (form) => namePartProblem(form.lastName, 'Фамилия')
  || namePartProblem(form.firstName, 'Имя')
  || (form.noMiddle ? '' : namePartProblem(form.middleName, 'Отчество'));

export const nameFormBody = (form) => ({
  last_name: form.lastName.trim(),
  first_name: form.firstName.trim(),
  ...(form.noMiddle || !form.middleName.trim() ? {} : { middle_name: form.middleName.trim() }),
});

// Студент в списках преподавателя (api-v1.md, раздел 2): тёзка — с постоянным номером в скобках.
export const studentLabel = (s) => {
  const name = s.student?.full_name || s.student_name || 'Студент';
  return s.student?.has_namesake && s.student.namesake_number ? `${name} (${s.student.namesake_number})` : name;
};
