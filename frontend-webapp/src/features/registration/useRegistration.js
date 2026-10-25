import { useState, useEffect } from 'react';
import { fetchMe, registerStudent, registerTeacher, sendTeacherCode } from '../../api/auth';
import { readError } from '../../api/client';
import { notifyHaptic } from '../../telegram/webApp';

const STUDENT_INPUT_ALLOWED_RE = /[^А-ЯЁа-яё.,\s\d]/g;
const STUDENT_REG_RE = /^[А-ЯЁ][а-яё]+\s[А-ЯЁ]\.[А-ЯЁ]\.,\s\d{6}$/;
const OFFLINE = 'Нет связи с сервером. Попробуйте позже.';
const ROLE_TITLES = { student: 'студент', teacher: 'преподаватель', admin: 'администратор' };

const normalizeStudentInput = (value) => value.trim().replace(/\s+/g, ' ');

// Что не так в строке «Иванов И.И., 321702»; пусто — всё верно.
export const studentInputProblem = (value) => {
  const input = normalizeStudentInput(value);
  if (!input) return 'Введите ФИО и группу.';
  return STUDENT_REG_RE.test(input) ? '' : 'Пример: Иванов И.И., 321702';
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

  const reset = () => {
    signOut();
    clearForm();
  };

  // --- Регистрация студента ---
  const changeRegInput = (value) => { setRegInput(value.replace(STUDENT_INPUT_ALLOWED_RE, '')); setRegError(''); };

  const submitStudent = async (e) => {
    e?.preventDefault();
    setRegError('');
    const normalizedInput = normalizeStudentInput(regInput);
    const problem = studentInputProblem(normalizedInput);
    if (problem) { setRegError(problem); return; }
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
        setRegError(err.error || 'Не удалось зарегистрироваться.');
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
        const err = await readError(res);
        setRegError(err.error || 'Не удалось зарегистрироваться.');
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
    regInput, changeRegInput, submitStudent,
    teacherEmail, setTeacherEmail, sendingCode, sendCode,
    regCode, setRegCode, codeTimeLeft, resendCode, confirmCode, backToEmail,
  };
}
