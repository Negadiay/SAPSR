import { useState } from 'react';

const onboardedKey = (role) => `sapsr_onboarded_${role}`;

// Справка открывается сама при первом входе под ролью и кнопкой «?» в заголовке.
export function useHelp({ role }) {
  const [open, setOpen] = useState(false);

  const close = () => {
    setOpen(false);
    try { localStorage.setItem(onboardedKey(role), '1'); } catch { /* покажется ещё раз — не страшно */ }
  };

  const maybeOpen = (forRole) => {
    try {
      if (!localStorage.getItem(onboardedKey(forRole))) setOpen(true);
    } catch { setOpen(true); }
  };

  return { open, show: () => setOpen(true), close, maybeOpen };
}
