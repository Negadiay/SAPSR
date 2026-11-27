import { useState, useEffect } from 'react';
import { fetchMe, registerStudent, registerTeacher, sendTeacherCode } from '../../api/auth';
import { readError } from '../../api/client';
import { notifyHaptic, showAlert } from '../../telegram/webApp';
import { nameFormBody, nameFormProblem } from '../../lib/names';

const OFFLINE = 'Нет связи с сервером. Попробуйте позже.';
const ROLE_TITLES = { student: 'студент', teacher: 'преподаватель', admin: 'администратор' };

const EMPTY_STUDENT = { lastName: '', firstName: '', middleName: '', noMiddle: false, group: '' };

export const groupProblem = (group) => (/^\d{6}$/.test(group || '') ? '' : 'Номер группы — шесть цифр.');

// Что не так в форме регистрации студента; пусто — всё верно.
export const studentFormProblem = (form) => nameFormProblem(form) || groupProblem(form.group);

// Состояние и действия экранов выбора роли, регистрации и подтверждения кода.
export function useRegistration({ session, onRegistered, onLoggedIn }) {
  const { step, setStep, setUserRole, setCurrentUserId, enter, signOut } = session;

  const [regError, setRegError]       = useState('');
  const [registering, setRegistering] = useState(false);

  // Регистрация студента
  const [student, setStudent]         = useState(EMPTY_STUDENT);

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
    setStudent(EMPTY_STUDENT); setRegCode(''); setRegError(''); setTeacherEmail('');
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
        setRegError('Не удалось войти. Закройте приложение и откройте снова.');
        return;
      }
      const data = await res.json();
      const dbRole = data.role && data.role !== 'NONE' ? data.role.toLowerCase() : '';
      if (dbRole !== role) {
        setRegError(`Вы не зарегистрированы как ${ROLE_TITLES[role]}.`);
        return;
      }
      if (data.telegram_id) setCurrentUserId(String(data.telegram_id));
      onLoggedIn?.();
      enter(role, data);
    } catch {
      setRegError(OFFLINE);
    }
  };

  const backToRole = () => { setRegError(''); setStep('role'); };

  // Один аккаунт Telegram — один человек (решение 15): на 409 показываем текст сервера
  // и открываем главный экран той роли, в которой аккаунт уже зарегистрирован.
  const failRegistration = async (res, fallback) => {
    const err = await readError(res);
    const text = err.message || err.error || fallback;
    setRegError(text);
    if (res.status !== 409 || err.code !== 'ALREADY_REGISTERED') return;
    try {
      const me = await fetchMe();
      const data = me.ok ? await me.json() : null;
      const role = data?.role && data.role !== 'NONE' ? data.role.toLowerCase() : '';
      if (!role) return;
      showAlert(text);
      if (data.telegram_id) setCurrentUserId(String(data.telegram_id));
      enter(role, data);
    } catch { /* остаёмся на форме с текстом ошибки */ }
  };

  const reset = () => {
    signOut();
    clearForm();
  };

  // --- Регистрация студента ---
  const changeStudent = (next) => {
    setStudent({ ...next, group: (next.group || '').replace(/\D/g, '').slice(0, 6) });
    setRegError('');
  };

  const submitStudent = async (e) => {
    e?.preventDefault();
    setRegError('');
    const problem = studentFormProblem(student);
    if (problem) { setRegError(problem); return; }
    setRegistering(true);
    try {
      const res = await registerStudent({ ...nameFormBody(student), group: student.group });
      if (res.ok) {
        notifyHaptic('success');
        enter('student');
        onRegistered('student');
      } else {
        await failRegistration(res, 'Не удалось зарегистрироваться.');
      }
    } catch { setRegError(OFFLINE); }
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
        setRegError(err.error || 'Не удалось отправить код.');
      }
    } catch { setRegError(OFFLINE); }
    finally { setSendingCode(false); }
  };

  const sendCode = async (e) => {
    e?.preventDefault();
    setRegError('');
    const email = teacherEmail.trim().toLowerCase();
    if (!email) { setRegError('Введите почту.'); return; }
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
    e?.preventDefault();
    setRegError('');
    if (!regCode.trim()) { setRegError('Введите код из письма.'); return; }
    if (codeTimeLeft <= 0) { setRegError('Код устарел — запросите новый.'); return; }
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
        await failRegistration(res, 'Не удалось зарегистрироваться.');
      }
    } catch { setRegError(OFFLINE); }
    finally { setRegistering(false); }
  };

  const backToEmail = () => {
    setStep('register'); setRegError(''); setRegCode(''); setCodeExpiresAt(null); setCodeTimeLeft(0);
  };

  return {
    regError, registering,
    selectRole, loginExisting, backToRole, reset,
    student, changeStudent, submitStudent,
    teacherEmail, setTeacherEmail, sendingCode, sendCode,
    regCode, setRegCode, codeTimeLeft, resendCode, confirmCode, backToEmail,
  };
}
