import { useState, useEffect } from 'react';
import { fetchMe, registerStudent, registerTeacher, sendTeacherCode } from '../../api/auth';
import { readError } from '../../api/client';
import { notifyHaptic } from '../../telegram/webApp';

const STUDENT_INPUT_ALLOWED_RE = /[^А-ЯЁа-яё.,\s\d]/g;
const STUDENT_REG_RE = /^[А-ЯЁ][а-яё]+\s[А-ЯЁ]\.[А-ЯЁ]\.,\s\d{6}$/;

const registrationErrorText = (message, fallback) => {
  const text = String(message || '').toLowerCase();
  if (text.includes('не найден') && (text.includes('iis') || text.includes('иис'))) {
    return 'Почта не обнаружена в ИИС БГУИР';
  }
  return message || fallback;
};

// Состояние и действия экранов выбора роли, регистрации и подтверждения кода.
export function useRegistration({ session, onRegistered, onLoggedIn }) {
  const { step, setStep, setUserRole, setCurrentUserId, enter, signOut } = session;

  const [regError, setRegError]       = useState('');
  const [registering, setRegistering] = useState(false);

  // Регистрация студента
  const [regInput, setRegInput]       = useState('');

  // Регистрация преподавателя
  const [teacherEmail, setTeacherEmail]   = useState('');
  const [regCode, setRegCode]             = useState('');
  const [sendingCode, setSendingCode]     = useState(false);
  const [codeExpiresAt, setCodeExpiresAt] = useState(null);
  const [codeTimeLeft, setCodeTimeLeft]   = useState(0);

  useEffect(() => {
    if (step !== 'confirm_code' || !codeExpiresAt) return undefined;
    const tick = () => {
      setCodeTimeLeft(Math.max(0, Math.ceil((codeExpiresAt - Date.now()) / 1000)));
    };
    tick();
    const timerId = window.setInterval(tick, 1000);
    return () => window.clearInterval(timerId);
  }, [step, codeExpiresAt]);

  const clearForm = () => {
    setRegInput(''); setRegCode(''); setRegError(''); setTeacherEmail('');
  };

  const selectRole = (role) => {
    setUserRole(role);
    clearForm();
    setStep('register');
  };

  const loginExisting = async (role) => {
    setRegError('');
    try {
      const res = await fetchMe();
      if (!res.ok) {
        setRegError('Не удалось войти. Откройте мини-приложение из Telegram ещё раз.');
        return;
      }
      const data = await res.json();
      const dbRole = data.role && data.role !== 'NONE' ? data.role.toLowerCase() : '';
      if (dbRole !== role) {
        setRegError('Для этой роли нет сохранённой регистрации');
        return;
      }
      if (data.telegram_id) setCurrentUserId(String(data.telegram_id));
      onLoggedIn();
      enter(role);
    } catch {
      setRegError('Сервер недоступен');
    }
  };

  const backToRole = () => setStep('role');

  const reset = () => {
    signOut();
    clearForm();
  };

  // --- Регистрация студента ---
  const changeRegInput = (value) => setRegInput(value.replace(STUDENT_INPUT_ALLOWED_RE, ''));

  const submitStudent = async (e) => {
    e.preventDefault();
    setRegError('');
    const normalizedInput = regInput.trim().replace(/\s+/g, ' ');
    const match = normalizedInput.match(STUDENT_REG_RE);
    if (!match) { setRegError('Формат: Иванов И.И., 123456'); return; }
    const groupNumber = normalizedInput.slice(-6);
    const namePart = normalizedInput.slice(0, -8);
    const fullName = namePart + ' (гр. ' + groupNumber + ')';
    setRegistering(true);
    try {
      const res = await registerStudent(fullName);
      if (res.ok) {
        notifyHaptic('success');
        enter('student');
        onRegistered('student');
      } else {
        const err = await readError(res);
        setRegError(err.error || 'Ошибка регистрации');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setRegistering(false); }
  };

  // --- Регистрация преподавателя ---
  const requestCode = async (email, onSent) => {
    setSendingCode(true);
    try {
      const res = await sendTeacherCode(email);
      if (res.ok) {
        const data = await res.json().catch(() => ({}));
        const expiresIn = Number(data.expires_in_seconds) || 300;
        onSent?.();
        setCodeExpiresAt(Date.now() + expiresIn * 1000);
        setRegCode('');
      } else {
        const err = await readError(res);
        setRegError(registrationErrorText(err.error, 'Ошибка отправки кода'));
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setSendingCode(false); }
  };

  const sendCode = async (e) => {
    e?.preventDefault();
    setRegError('');
    const email = teacherEmail.trim().toLowerCase();
    if (!email) { setRegError('Введите email'); return; }
    await requestCode(email, () => {
      setTeacherEmail(email);
      setStep('confirm_code');
    });
  };

  const resendCode = async () => {
    setRegError('');
    await requestCode(teacherEmail);
  };

  const confirmCode = async (e) => {
    e.preventDefault();
    setRegError('');
    if (!regCode.trim()) { setRegError('Введите код из письма'); return; }
    if (codeTimeLeft <= 0) { setRegError('Срок действия кода истёк. Нажмите «Отправить код повторно».'); return; }
    setRegistering(true);
    try {
      const res = await registerTeacher(teacherEmail.trim(), regCode.trim());
      if (res.ok) {
        notifyHaptic('success');
        enter('teacher');
        setCodeExpiresAt(null);
        setCodeTimeLeft(0);
        onRegistered('teacher');
      } else {
        const err = await readError(res);
        setRegError(err.error || 'Ошибка регистрации');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setRegistering(false); }
  };

  const backToEmail = () => {
    setStep('register'); setRegError(''); setRegCode(''); setCodeExpiresAt(null); setCodeTimeLeft(0);
  };

  return {
    regError, registering,
    selectRole, loginExisting, backToRole, reset,
    regInput, changeRegInput, submitStudent,
    teacherEmail, setTeacherEmail, sendingCode, sendCode,
    regCode, setRegCode, codeTimeLeft, resendCode, confirmCode, backToEmail,
  };
}
