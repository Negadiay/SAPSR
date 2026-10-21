// «Фамилия И. О.» из данных GET /me. У аккаунтов старой формы («Иванов И.И. (гр. 321702)») имени
// и отчества в базе нет — инициалы берутся из full_name.
const initial = (name) => (name ? `${name.trim()[0].toUpperCase()}.` : '');

export const lastNameOf = (me) => (me ? me.last_name || (me.full_name || '').trim().split(/\s+/)[0] || '' : '');

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
