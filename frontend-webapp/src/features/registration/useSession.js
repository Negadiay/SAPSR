import { useState, useEffect } from 'react';
import { fetchMe } from '../../api/auth';
import { getTelegramUserId, initWebApp } from '../../telegram/webApp';

// Кто вошёл и какой экран показан: loading → role → register → confirm_code → main.
export function useSession() {
  const [step, setStep]                     = useState('loading');
  const [userRole, setUserRole]             = useState('');
  const [registeredRole, setRegisteredRole] = useState('');
  const [currentUserId, setCurrentUserId]   = useState(getTelegramUserId);
  // Свои данные из GET /me: ФИО и группа для настроек и имени файла по форме.
  const [profile, setProfile]               = useState(null);

  const refreshProfile = async () => {
    try {
      const res = await fetchMe();
      if (res.ok) setProfile(await res.json());
    } catch (err) { console.warn('Profile error:', err); }
  };

  // me — данные GET /me, если они уже есть; после регистрации их ещё нет — запрашиваем.
  const enter = (role, me) => {
    setUserRole(role);
    setRegisteredRole(role);
    setStep('main');
    if (me) setProfile(me);
    else refreshProfile();
  };

  const signOut = () => {
    setStep('role');
    setUserRole('');
    setProfile(null);
  };

  const checkAuth = async () => {
    try {
      const res = await fetchMe();
      if (res.ok) {
        const data = await res.json();
        if (data.role && data.role !== 'NONE') {
          setCurrentUserId(data.telegram_id ? String(data.telegram_id) : '');
          enter(data.role.toLowerCase(), data);
          return;
        }
      }
    } catch (err) { console.warn('Auth error:', err); }
    setStep('role');
  };

  useEffect(() => {
    initWebApp();
    checkAuth();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return {
    step, setStep, userRole, setUserRole, registeredRole,
    currentUserId, setCurrentUserId, enter, signOut, profile, refreshProfile,
  };
}
