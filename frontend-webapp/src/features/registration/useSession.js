import { useState, useEffect } from 'react';
import { fetchMe } from '../../api/auth';
import { getTelegramUserId, initWebApp } from '../../telegram/webApp';

// Кто вошёл и какой экран показан: loading → role → register → confirm_code → main.
export function useSession() {
  const [step, setStep]                     = useState('loading');
  const [userRole, setUserRole]             = useState('');
  const [registeredRole, setRegisteredRole] = useState('');
  const [currentUserId, setCurrentUserId]   = useState(getTelegramUserId);

  const enter = (role) => {
    setUserRole(role);
    setRegisteredRole(role);
    setStep('main');
  };

  const signOut = () => {
    setStep('role');
    setUserRole('');
  };

  const checkAuth = async () => {
    try {
      const res = await fetchMe();
      if (res.ok) {
        const data = await res.json();
        if (data.role && data.role !== 'NONE') {
          setCurrentUserId(data.telegram_id ? String(data.telegram_id) : '');
          enter(data.role.toLowerCase());
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
    currentUserId, setCurrentUserId, enter, signOut,
  };
}
