import { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import './App.css';

const API_BASE = import.meta.env.VITE_API_BASE || '/api/v1';
const MotionDiv = motion.div;
const AUTO_REFRESH_INTERVAL_MS = 12000;
const COMMENT_TEMPLATE_LIMIT = 20;
const COMMENT_SUGGESTION_LIMIT = 3;
const LAST_TEACHER_KEY = 'sapsr_last_teacher_id';
const FONT_SIZE_OPTIONS = ['small', 'normal', 'large', 'xlarge'];
const FONT_SIZE_LABELS = {
  small: 'Мелкий',
  normal: 'Обычный',
  large: 'Крупный',
  xlarge: 'Очень крупный',
};
const STUDENT_INPUT_ALLOWED_RE = /[^А-ЯЁа-яё.,\s\d]/g;
const STUDENT_REG_RE = /^[А-ЯЁ][а-яё]+\s[А-ЯЁ]\.[А-ЯЁ]\.,\s\d{6}$/;

const getTelegramUserId = (initData) => {
  try {
    const user = new URLSearchParams(initData).get('user');
    return user ? String(JSON.parse(user).id || '') : '';
  } catch {
    return '';
  }
};

const formatCountdown = (seconds) => {
  const safeSeconds = Math.max(0, seconds);
  const minutes = Math.floor(safeSeconds / 60);
  const rest = String(safeSeconds % 60).padStart(2, '0');
  return `${minutes}:${rest}`;
};

// --- Нечёткий поиск ---
const levenshtein = (a, b) => {
  const m = a.length, n = b.length;
  const dp = Array.from({ length: m + 1 }, (_, i) =>
    Array.from({ length: n + 1 }, (_, j) => (j === 0 ? i : 0))
  );
  for (let j = 1; j <= n; j++) dp[0][j] = j;
  for (let i = 1; i <= m; i++)
    for (let j = 1; j <= n; j++)
      dp[i][j] = a[i - 1] === b[j - 1]
        ? dp[i - 1][j - 1]
        : 1 + Math.min(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]);
  return dp[m][n];
};

const fuzzyToken = (token, haystack) => {
  if (haystack.includes(token)) return true;
  const words = haystack.split(/[\s,._\-]+/).filter(Boolean);
  const threshold = token.length <= 3 ? 0 : token.length <= 5 ? 1 : 2;
  return words.some(word => {
    if (word.startsWith(token)) return true;
    if (word.length >= token.length - 1 && word.length <= token.length + 2)
      return levenshtein(token, word) <= threshold;
    return false;
  });
};

// --- Туториал ---
const STUDENT_STEPS = [
  { refKey: null,            text: 'Добро пожаловать в SAPSR — систему автоматической проверки оформления курсовых работ по стандартам БГУИР! 👋' },
  { refKey: 'teacherSel',   text: 'Выберите научного руководителя из списка. Показаны только преподаватели вашей группы, указанной при регистрации.' },
  { refKey: 'fileUpload',   text: 'Прикрепите PDF-файл курсовой работы. Система принимает только формат .pdf размером до 50 МБ.' },
  { refKey: 'submitBtn',    text: 'Нажмите «Отправить» — система автоматически проверит форматирование по ГОСТу и уведомит преподавателя.' },
  { refKey: 'navNotif',     text: 'Во вкладке «Мои работы» отображаются все ваши работы, статус проверки и вердикт преподавателя. Здесь же можно скачать отчёт об ошибках (кнопка 📥).' },
  { refKey: null,            text: '📥 Скачанные файлы можно найти в Telegram: нажмите три точки (•••) в правом верхнем углу мини-приложения, затем проверьте вкладку «Загрузки» на устройстве.' },
  { refKey: 'navSettings',  text: 'В настройках можно переключить светлую/тёмную тему, изменить размер шрифта и включить контрастный режим для улучшенной видимости.' },
];

const TEACHER_STEPS = [
  { refKey: null,            text: 'Добро пожаловать! В SAPSR студенты присылают работы, уже прошедшие автоматическую проверку оформления по ГОСТу. 👋' },
  { refKey: 'submissions',   text: 'Здесь отображаются работы, ожидающие вашей проверки. Нажмите на карточку, чтобы раскрыть действия: скачать PDF, принять или отправить на доработку.' },
  { refKey: 'teacherSearch', text: 'Нечёткий поиск фильтрует работы по имени студента, группе или файлу — даже при опечатках. Попробуйте ввести фамилию или номер группы.' },
  { refKey: 'navHistory',    text: 'Во вкладке «История» хранятся все ранее проверенные вами работы — удобно проверить, действительно ли студент сдавал работу.' },
  { refKey: 'navNotes',      text: 'Во вкладке «Заметки» записывайте напоминания о студентах. Кнопка 🔍 в заметке автоматически найдёт работы упомянутого студента.' },
  { refKey: 'addNoteBtn',    text: '✍️ Попробуйте прямо сейчас! Нажмите «+ Новая заметка» и создайте первую заметку — например, напишите имя студента. Затем нажмите «Далее» для продолжения.' },
  { refKey: null,            text: 'Вы получаете уведомление в Telegram каждый раз, когда студент присылает новую работу.' },
  { refKey: 'navSettings',   text: 'В настройках можно изменить тему оформления, размер шрифта и включить контрастный режим.' },
];

function TutorialOverlay({ steps, step, onNext, onSkip, refs }) {
  const current = steps[step];
  const targetRef = current?.refKey ? refs[current.refKey] : null;
  const [rect, setRect] = useState(null);

  useEffect(() => {
    if (targetRef?.current) {
      setRect(targetRef.current.getBoundingClientRect());
    } else {
      setRect(null);
    }
  }, [step, targetRef]);

  const PAD = 6;
  const TOOLTIP_W = Math.min(300, window.innerWidth - 24);
  const TOOLTIP_H = 170;
  const BOTTOM_SAFE_AREA = 110;
  const vw = window.innerWidth;
  const vh = window.innerHeight;

  let tooltipStyle;
  if (rect) {
    const spaceBelow = vh - rect.bottom - PAD - BOTTOM_SAFE_AREA;
    let top = spaceBelow >= TOOLTIP_H
      ? rect.bottom + PAD + 10
      : Math.max(12, rect.top - PAD - TOOLTIP_H - 10);
    top = Math.max(12, Math.min(top, vh - TOOLTIP_H - BOTTOM_SAFE_AREA));
    let left = rect.left + rect.width / 2 - TOOLTIP_W / 2;
    left = Math.max(12, Math.min(left, vw - TOOLTIP_W - 12));
    tooltipStyle = { top, left, width: TOOLTIP_W };
  } else {
    tooltipStyle = { top: '38%', left: '50%', transform: 'translateX(-50%)', width: TOOLTIP_W };
  }

  return (
    <div className="tutorial-overlay" onClick={onNext}>
      {rect && (
        <div className="tutorial-spotlight" style={{
          top:    rect.top    - PAD,
          left:   rect.left   - PAD,
          width:  rect.width  + PAD * 2,
          height: rect.height + PAD * 2,
        }} />
      )}
      <div className="tutorial-tooltip" style={tooltipStyle} onClick={e => e.stopPropagation()}>
        <p>{current?.text}</p>
        <div className="tutorial-footer">
          <span className="tutorial-counter">{step + 1} / {steps.length}</span>
          <button className="tutorial-skip-btn" onClick={onSkip}>Пропустить</button>
          <button className="tutorial-next-btn" onClick={onNext}>
            {step + 1 >= steps.length ? 'Готово' : 'Далее →'}
          </button>
        </div>
      </div>
    </div>
  );
}

// --- Авторы ---
const AUTHORS = [
  { name: 'Бузычков Н.Ф.',  role: 'Менеджер проекта' },
  { name: 'Жерко Н.А.',     role: 'Бэкенд-разработчик' },
  { name: 'Котко П.А.',     role: 'Фронтенд-разработчик' },
  { name: 'Халилов Р.Э.',   role: 'Интеграция сервисов' },
];

function AuthorsModal({ onClose }) {
  return (
    <div className="confirm-overlay" onClick={onClose}>
      <div className="authors-dialog" onClick={e => e.stopPropagation()}>
        <h3 className="authors-title">✨ Авторы</h3>
        <div className="authors-list">
          {AUTHORS.map(a => (
            <div key={a.name} className="authors-item">
              <span className="authors-name">{a.name}</span>
              <span className="authors-role">{a.role}</span>
            </div>
          ))}
        </div>
        <button className="secondary-btn authors-close-btn" onClick={onClose}>Закрыть</button>
      </div>
    </div>
  );
}

function App() {
  const [step, setStep]               = useState('loading');
  const [activeTab, setActiveTab]     = useState(0);
  const [direction, setDirection]     = useState(0);
  const [userRole, setUserRole]       = useState('');
  const [registeredRole, setRegisteredRole] = useState('');
  const [currentUserId, setCurrentUserId] = useState('');

  // Регистрация студента
  const [regInput, setRegInput]       = useState('');
  const [regError, setRegError]       = useState('');
  const [registering, setRegistering] = useState(false);

  // Регистрация преподавателя
  const [teacherEmail, setTeacherEmail]   = useState('');
  const [regCode, setRegCode]             = useState('');
  const [sendingCode, setSendingCode]     = useState(false);
  const [codeExpiresAt, setCodeExpiresAt] = useState(null);
  const [codeTimeLeft, setCodeTimeLeft]   = useState(0);

  // Загрузка файла (студент)
  const [file, setFile]                   = useState(null);
  const [status, setStatus]               = useState('');
  const [teachers, setTeachers]           = useState([]);
  const [selectedTeacherId, setSelectedTeacherId] = useState('');
  const [uploading, setUploading]         = useState(false);
  const [submissions, setSubmissions]     = useState([]);

  // Дашборд преподавателя
  const [teacherSubmissions, setTeacherSubmissions] = useState([]);
  const [teacherHistory, setTeacherHistory]         = useState([]);
  const [expandedId, setExpandedId]       = useState(null);
  const [revisionId, setRevisionId]       = useState(null);
  const [revisionComment, setRevisionComment] = useState('');
  const [commentTemplates, setCommentTemplates] = useState([]);
  const [teacherSearch, setTeacherSearch] = useState('');
  const [verdictLoading, setVerdictLoading] = useState(false);

  // Заметки преподавателя
  const [teacherNotes, setTeacherNotes]   = useState([]);
  const [noteInput, setNoteInput]         = useState('');
  const [editingNoteId, setEditingNoteId] = useState(null);

  // Отзыв работы студентом
  const [withdrawing, setWithdrawing]         = useState(null);
  const [withdrawConfirmId, setWithdrawConfirmId] = useState(null);

  // Настройки
  const [theme, setTheme]       = useState(() => localStorage.getItem('sapsr_theme') === 'dark' ? 'dark' : 'light');
  const [fontSize, setFontSize] = useState(() => localStorage.getItem('sapsr_fontsize') || 'normal');
  const [contrast, setContrast] = useState(() => localStorage.getItem('sapsr_contrast') === '1');

  // Авторы
  const [showAuthors, setShowAuthors] = useState(false);

  // Туториал
  const [tutorialActive, setTutorialActive] = useState(false);
  const [tutorialStep, setTutorialStep]     = useState(0);

  // Refs для туториала
  const refs = {
    teacherSel:    useRef(null),
    fileUpload:    useRef(null),
    submitBtn:     useRef(null),
    navNotif:      useRef(null),
    navSettings:   useRef(null),
    navNotes:      useRef(null),
    navHistory:    useRef(null),
    submissions:   useRef(null),
    teacherSearch: useRef(null),
    addNoteBtn:    useRef(null),
  };

  // Свайп-навигация — исключаем слайдеры
  const swipeStartX = useRef(0);
  const swipeStartY = useRef(0);
  const swipeActive = useRef(false);

  useEffect(() => {
    if (step !== 'main') return;
    const onStart = (e) => {
      if (e.target.tagName === 'INPUT' && e.target.type === 'range') {
        swipeActive.current = false;
        return;
      }
      swipeStartX.current = e.touches[0].clientX;
      swipeStartY.current = e.touches[0].clientY;
      swipeActive.current = true;
    };
    const onEnd = (e) => {
      if (!swipeActive.current) return;
      swipeActive.current = false;
      const dx = e.changedTouches[0].clientX - swipeStartX.current;
      const dy = e.changedTouches[0].clientY - swipeStartY.current;
      if (Math.abs(dx) < 50 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
      const tabCount = userRole === 'teacher' ? 4 : 3;
      setDirection(dx < 0 ? 1 : -1);
      setActiveTab(prev => dx < 0 ? (prev + 1) % tabCount : (prev - 1 + tabCount) % tabCount);
    };
    document.addEventListener('touchstart', onStart, { passive: true });
    document.addEventListener('touchend', onEnd, { passive: true });
    return () => {
      document.removeEventListener('touchstart', onStart);
      document.removeEventListener('touchend', onEnd);
    };
  }, [step, userRole]);

  const tg = window.Telegram?.WebApp;
  const initData = tg?.initData || '';
  const apiHeaders = (extra = {}) => ({ 'Authorization': initData, ...extra });

  useEffect(() => {
    if (step !== 'confirm_code' || !codeExpiresAt) return undefined;
    const tick = () => {
      setCodeTimeLeft(Math.max(0, Math.ceil((codeExpiresAt - Date.now()) / 1000)));
    };
    tick();
    const timerId = window.setInterval(tick, 1000);
    return () => window.clearInterval(timerId);
  }, [step, codeExpiresAt]);

  const getTutorialTabIndex = (role, refKey) => {
    if (role === 'teacher') {
      if (refKey === 'teacherSearch' || refKey === 'submissions') return 0;
      if (refKey === 'navHistory') return 1;
      if (refKey === 'navNotes' || refKey === 'addNoteBtn') return 2;
      if (refKey === 'navSettings') return 3;
      return null;
    }
    if (['teacherSel', 'fileUpload', 'submitBtn'].includes(refKey)) return 0;
    if (refKey === 'navNotif') return 1;
    if (refKey === 'navSettings') return 2;
    return null;
  };

  const openTutorialStep = (role, nextStep) => {
    const steps = role === 'teacher' ? TEACHER_STEPS : STUDENT_STEPS;
    const tabIndex = getTutorialTabIndex(role, steps[nextStep]?.refKey);
    if (tabIndex !== null && tabIndex !== activeTab) {
      setDirection(tabIndex > activeTab ? 1 : -1);
      setActiveTab(tabIndex);
    }
    setTutorialStep(nextStep);
  };

  const getTemplateStorageKey = () => `sapsr_revision_templates_${currentUserId || userRole || 'teacher'}`;

  const sortTemplates = (templates) => [...templates]
    .sort((a, b) => (b.count - a.count) || (b.lastUsed - a.lastUsed))
    .slice(0, COMMENT_TEMPLATE_LIMIT);

  const saveCommentTemplates = (templates) => {
    const sorted = sortTemplates(templates);
    setCommentTemplates(sorted);
    localStorage.setItem(getTemplateStorageKey(), JSON.stringify(sorted));
  };

  const rememberCommentTemplate = (comment) => {
    const text = comment.trim();
    if (!text) return;
    const now = Date.now();
    const existing = commentTemplates.find(t => t.text === text);
    const next = existing
      ? commentTemplates.map(t => t.text === text ? { ...t, count: t.count + 1, lastUsed: now } : t)
      : [...commentTemplates, { text, count: 1, lastUsed: now }];
    saveCommentTemplates(next);
  };

  // --- Заметки ---
  const getNotesKey = () => `sapsr_notes_${currentUserId || 'teacher'}`;

  const loadNotes = () => {
    try { return JSON.parse(localStorage.getItem(getNotesKey()) || '[]'); } catch { return []; }
  };

  const saveNotes = (notes) => {
    setTeacherNotes(notes);
    localStorage.setItem(getNotesKey(), JSON.stringify(notes));
  };

  const handleSaveNote = () => {
    const text = noteInput.trim();
    if (!text) return;
    saveNotes([...teacherNotes, { id: Date.now(), text }]);
    setNoteInput('');
    setEditingNoteId(null);
  };

  const handleUpdateNote = (id) => {
    const text = noteInput.trim();
    if (!text) return;
    saveNotes(teacherNotes.map(n => n.id === id ? { ...n, text } : n));
    setNoteInput('');
    setEditingNoteId(null);
  };

  const handleDeleteNote = (id) => {
    saveNotes(teacherNotes.filter(n => n.id !== id));
  };

  // Улучшенный анализатор заметок — ищет фамилию по суффиксам
  const handleFindFromNote = (text) => {
    const groupMatch = text.match(/\d{6}/);

    const SURNAME_ENDINGS = /(?:ов|ев|ёв|ова|ева|ёва|ин|ина|ын|ына|ий|ая|ой|ский|цкий|ская|цкая|ко|ук|юк|ич|евич|ович|ан|ян|ец|нко)$/i;
    const SKIP_ENDINGS   = /(?:[тТ][ьЬ]([сС][яЯ])?|[чЧ][ьЬ]|[шШ][ьЬ]|[жЖ][ьЬ]|ние|ость|ство|ание|ение)$/;
    const SKIP_WORDS     = /^(?:Это|Как|Что|Где|Его|Её|Их|Все|Всё|Тот|Для|При|Про|Без|Над|Под|Через|Между|Среди|Около|Снова|Потом|Когда|Такой|Такая|Такое|Такие|Очень|Нужно|Можно|Нельзя|Январ|Феврал|Март|Апрел|Май|Июн|Июл|Август|Сентябр|Октябр|Ноябр|Декабр|Понедельник|Вторник|Среда|Четверг|Пятница|Суббота|Воскресенье)$/i;

    const capitalWords = [...text.matchAll(/\b[А-ЯЁ][а-яё]{2,}\b/g)].map(m => m[0]);

    // Приоритет — слова с типичными суффиксами фамилий
    let surname = capitalWords.find(w =>
      !SKIP_ENDINGS.test(w) && !SKIP_WORDS.test(w) && SURNAME_ENDINGS.test(w)
    );
    // Запасной вариант — любое подходящее заглавное слово
    if (!surname) {
      surname = capitalWords.find(w => !SKIP_ENDINGS.test(w) && !SKIP_WORDS.test(w));
    }

    const query = [surname, groupMatch?.[0]].filter(Boolean).join(' ');
    setTeacherSearch(query);
    setDirection(-1);
    setActiveTab(0);
  };

  // --- Отзыв работы ---
  const handleWithdrawWork = (submissionId) => setWithdrawConfirmId(submissionId);

  const confirmWithdraw = async () => {
    const id = withdrawConfirmId;
    setWithdrawConfirmId(null);
    setWithdrawing(id);
    try {
      await fetch(`${API_BASE}/submissions/${id}`, { method: 'DELETE', headers: apiHeaders() });
      fetchSubmissions();
    } finally { setWithdrawing(null); }
  };

  const revisionSuggestions = sortTemplates(commentTemplates)
    .filter(t => {
      const query = revisionComment.trim().toLowerCase();
      return !query || t.text.toLowerCase().includes(query);
    })
    .slice(0, COMMENT_SUGGESTION_LIMIT);

  const getBaseFileName = (name = '') => name.replace(/\.[^.]+$/, '').trim().toLowerCase() || name;

  const iterationMark = (submission) => {
    if (submission.status === 'PROCESSING') return '⏳';
    if (submission.status === 'REJECTED') return '❌';
    if (submission.teacher_verdict === 'REVISION') return '🔄';
    if (submission.status === 'SUCCESS') return '✅';
    return '•';
  };

  const buildStudentWorkGroups = () => {
    const groups = new Map();
    submissions.forEach((submission) => {
      const key = getBaseFileName(submission.file_name);
      const group = groups.get(key) || { key, fileName: submission.file_name, items: [] };
      group.items.push(submission);
      groups.set(key, group);
    });
    return [...groups.values()].map((group) => {
      const desc = [...group.items].sort((a, b) => new Date(b.created_at || 0) - new Date(a.created_at || 0));
      return { ...group, items: desc, timeline: [...desc].reverse(), latest: desc[0] };
    }).sort((a, b) => new Date(b.latest?.created_at || 0) - new Date(a.latest?.created_at || 0));
  };

  const studentWorkGroups = buildStudentWorkGroups();

  // Нечёткий поиск по работам преподавателя
  const filteredTeacherSubmissions = teacherSubmissions.filter((s) => {
    const query = teacherSearch.trim().toLowerCase();
    if (!query) return true;
    const tokens = query.split(/\s+/).filter(Boolean);
    const haystack = [s.student_name, s.file_name, s.created_at]
      .filter(Boolean).map(v => String(v).toLowerCase()).join(' ');
    return tokens.every(token => fuzzyToken(token, haystack));
  });

  useEffect(() => {
    tg?.ready();
    tg?.expand();
    checkAuth();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => { localStorage.setItem('sapsr_theme', theme); }, [theme]);
  useEffect(() => { localStorage.setItem('sapsr_fontsize', fontSize); }, [fontSize]);
  useEffect(() => { localStorage.setItem('sapsr_contrast', contrast ? '1' : '0'); }, [contrast]);
  useEffect(() => {
    const telegramUserId = getTelegramUserId(initData);
    if (telegramUserId) setCurrentUserId(telegramUserId);
  }, [initData]);

  useEffect(() => {
    if (userRole !== 'teacher') {
      setCommentTemplates([]);
      setTeacherNotes([]);
      return;
    }
    try {
      const raw = localStorage.getItem(getTemplateStorageKey());
      setCommentTemplates(raw ? sortTemplates(JSON.parse(raw)) : []);
    } catch {
      setCommentTemplates([]);
    }
    setTeacherNotes(loadNotes());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userRole, currentUserId]);

  useEffect(() => {
    if (step !== 'main') return undefined;
    if (userRole === 'student') {
      fetchSubmissions();
      const id = window.setInterval(fetchSubmissions, AUTO_REFRESH_INTERVAL_MS);
      return () => window.clearInterval(id);
    }
    if (userRole === 'teacher') {
      fetchTeacherSubmissions();
      fetchTeacherHistory();
      const id = window.setInterval(() => {
        fetchTeacherSubmissions();
        fetchTeacherHistory();
      }, AUTO_REFRESH_INTERVAL_MS);
      return () => window.clearInterval(id);
    }
    return undefined;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step, userRole]);

  const resolvedTheme = theme;

  const checkAuth = async () => {
    try {
      const res = await fetch(`${API_BASE}/me`, { headers: apiHeaders() });
      if (res.ok) {
        const data = await res.json();
        if (data.role && data.role !== 'NONE') {
          const role = data.role.toLowerCase();
          setCurrentUserId(data.telegram_id ? String(data.telegram_id) : '');
          setUserRole(role);
          setRegisteredRole(role);
          setStep('main');
          if (role === 'student') { fetchTeachers(); fetchSubmissions(); }
          else { fetchTeacherSubmissions(); fetchTeacherHistory(); }
          return;
        }
      }
    } catch (err) { console.warn('Auth error:', err); }
    setStep('role');
  };

  const fetchTeachers = async () => {
    try {
      const res = await fetch(`${API_BASE}/teachers`, { headers: apiHeaders() });
      if (res.ok) {
        const data = await res.json();
        setTeachers(data);
        if (data.length > 0) {
          const savedTeacherId = localStorage.getItem(LAST_TEACHER_KEY);
          const savedExists = data.some(t => String(t.telegram_id || t.id || 0) === savedTeacherId);
          setSelectedTeacherId(savedExists ? savedTeacherId : String(data[0].telegram_id || data[0].id || 0));
        }
      }
    } catch (err) { console.warn('Teachers error:', err); }
  };

  const fetchSubmissions = async () => {
    try {
      const res = await fetch(`${API_BASE}/submissions`, { headers: apiHeaders() });
      if (res.ok) setSubmissions(await res.json());
    } catch (err) { console.warn('Submissions error:', err); }
  };

  const fetchTeacherSubmissions = async () => {
    try {
      const res = await fetch(`${API_BASE}/teacher/submissions`, { headers: apiHeaders() });
      if (res.ok) setTeacherSubmissions(await res.json());
    } catch (err) { console.warn('Teacher submissions error:', err); }
  };

  const fetchTeacherHistory = async () => {
    try {
      const res = await fetch(`${API_BASE}/teacher/history`, { headers: apiHeaders() });
      if (res.ok) setTeacherHistory(await res.json());
    } catch (err) { console.warn('Teacher history error:', err); }
  };

  // --- Туториал ---
  const handleTutorialNext = () => {
    const steps = userRole === 'teacher' ? TEACHER_STEPS : STUDENT_STEPS;
    if (tutorialStep + 1 >= steps.length) {
      setTutorialActive(false);
      localStorage.setItem('sapsr_onboarded_' + userRole, '1');
    } else {
      openTutorialStep(userRole, tutorialStep + 1);
    }
  };

  const handleTutorialSkip = () => {
    setTutorialActive(false);
    localStorage.setItem('sapsr_onboarded_' + userRole, '1');
  };

  const maybeLaunchTutorial = (role) => {
    if (!localStorage.getItem('sapsr_onboarded_' + role)) {
      setTimeout(() => {
        setDirection(-1);
        setActiveTab(0);
        setTutorialStep(0);
        setTutorialActive(true);
      }, 600);
    }
  };

  // --- Регистрация студента ---
  const handleRegisterStudent = async (e) => {
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
      const res = await fetch(`${API_BASE}/register`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ role: 'STUDENT', full_name: fullName }),
      });
      if (res.ok) {
        tg?.HapticFeedback?.notificationOccurred('success');
        setUserRole('student');
        setRegisteredRole('student');
        setStep('main');
        fetchTeachers();
        maybeLaunchTutorial('student');
      } else {
        const err = await res.json().catch(() => ({}));
        setRegError(err.error || 'Ошибка регистрации');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setRegistering(false); }
  };

  // --- Регистрация преподавателя ---
  const handleTeacherSendCode = async (e) => {
    e?.preventDefault();
    setRegError('');
    const email = teacherEmail.trim().toLowerCase();
    if (!email) { setRegError('Введите email'); return; }
    setSendingCode(true);
    try {
      const res = await fetch(`${API_BASE}/register/send-code`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ email }),
      });
      if (res.ok) {
        const data = await res.json().catch(() => ({}));
        const expiresIn = Number(data.expires_in_seconds) || 300;
        setTeacherEmail(email);
        setCodeExpiresAt(Date.now() + expiresIn * 1000);
        setStep('confirm_code');
        setRegCode('');
      } else {
        const err = await res.json().catch(() => ({}));
        setRegError(err.error || 'Ошибка отправки кода');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setSendingCode(false); }
  };

  const handleResendCode = async () => {
    setRegError('');
    setSendingCode(true);
    try {
      const res = await fetch(`${API_BASE}/register/send-code`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ email: teacherEmail }),
      });
      if (res.ok) {
        const data = await res.json().catch(() => ({}));
        const expiresIn = Number(data.expires_in_seconds) || 300;
        setCodeExpiresAt(Date.now() + expiresIn * 1000);
        setRegCode('');
      } else {
        const err = await res.json().catch(() => ({}));
        setRegError(err.error || 'Ошибка отправки кода');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setSendingCode(false); }
  };

  const handleConfirmCode = async (e) => {
    e.preventDefault();
    setRegError('');
    if (!regCode.trim()) { setRegError('Введите код из письма'); return; }
    if (codeTimeLeft <= 0) { setRegError('Срок действия кода истёк. Нажмите «Отправить код повторно».'); return; }
    setRegistering(true);
    try {
      const res = await fetch(`${API_BASE}/register`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ role: 'TEACHER', full_name: '', email: teacherEmail.trim(), code: regCode.trim() }),
      });
      if (res.ok) {
        tg?.HapticFeedback?.notificationOccurred('success');
        setUserRole('teacher');
        setRegisteredRole('teacher');
        setStep('main');
        setCodeExpiresAt(null);
        setCodeTimeLeft(0);
        fetchTeacherSubmissions();
        fetchTeacherHistory();
        maybeLaunchTutorial('teacher');
      } else {
        const err = await res.json().catch(() => ({}));
        setRegError(err.error || 'Ошибка регистрации');
      }
    } catch { setRegError('Сервер недоступен'); }
    finally { setRegistering(false); }
  };

  // --- Скачивание ---
  const openDownloadUrl = (apiPath, filename) => {
    const url = `${API_BASE}${apiPath}?tg_auth=${encodeURIComponent(initData)}`;
    if (tg?.downloadFile) {
      tg.downloadFile({ url, file_name: filename });
    } else {
      window.open(url, '_blank');
    }
  };

  const downloadBlob = (blob, filename) => {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = filename;
    document.body.appendChild(a); a.click();
    document.body.removeChild(a); URL.revokeObjectURL(url);
  };

  const handleDownloadReport = async (id) => {
    if (tg?.platform && tg.platform !== 'unknown') {
      openDownloadUrl(`/submissions/${id}/report`, `report_${id}.pdf`);
    } else {
      try {
        const res = await fetch(`${API_BASE}/submissions/${id}/report`, { headers: apiHeaders() });
        if (res.ok) downloadBlob(await res.blob(), `report_${id}.pdf`);
        else openDownloadUrl(`/submissions/${id}/report`, `report_${id}.pdf`);
      } catch { openDownloadUrl(`/submissions/${id}/report`, `report_${id}.pdf`); }
    }
  };

  const handleDownloadPdf = async (id) => {
    if (tg?.platform && tg.platform !== 'unknown') {
      openDownloadUrl(`/teacher/submissions/${id}/pdf`, `submission_${id}.pdf`);
    } else {
      try {
        const res = await fetch(`${API_BASE}/teacher/submissions/${id}/pdf`, { headers: apiHeaders() });
        if (res.ok) downloadBlob(await res.blob(), `submission_${id}.pdf`);
        else openDownloadUrl(`/teacher/submissions/${id}/pdf`, `submission_${id}.pdf`);
      } catch { openDownloadUrl(`/teacher/submissions/${id}/pdf`, `submission_${id}.pdf`); }
    }
  };

  // --- Вердикт ---
  const handleVerdict = async (id, verdict, comment = '') => {
    setVerdictLoading(true);
    try {
      const res = await fetch(`${API_BASE}/teacher/submissions/${id}/verdict`, {
        method: 'POST',
        headers: apiHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ verdict, comment }),
      });
      if (res.ok) {
        if (verdict === 'REVISION') rememberCommentTemplate(comment);
        tg?.HapticFeedback?.notificationOccurred('success');
        setRevisionId(null); setRevisionComment(''); setExpandedId(null);
        fetchTeacherSubmissions();
        fetchTeacherHistory();
      } else {
        const err = await res.json().catch(() => ({}));
        alert(err.error || 'Ошибка');
      }
    } catch { alert('Сервер недоступен'); }
    finally { setVerdictLoading(false); }
  };

  // --- Загрузка файла ---
  const handleSubmitFile = async (e) => {
    e.preventDefault();
    if (!file || uploading) return;
    setUploading(true); setStatus('⏳ Загрузка...');
    try {
      const formData = new FormData();
      formData.append('file', file);
      if (selectedTeacherId) {
        formData.append('teacher_id', selectedTeacherId);
        localStorage.setItem(LAST_TEACHER_KEY, selectedTeacherId);
      }
      const res = await fetch(`${API_BASE}/upload`, { method: 'POST', headers: { 'Authorization': initData }, body: formData });
      if (res.ok) {
        setStatus('✅ Работа успешно отправлена!'); setFile(null);
        tg?.HapticFeedback?.notificationOccurred('success');
        fetchSubmissions();
        tg?.showPopup({ title: 'Готово!', message: 'Файл отправлен на проверку.', buttons: [{ type: 'ok' }] });
      } else {
        tg?.HapticFeedback?.notificationOccurred('error');
        if (res.status === 413) {
          setStatus('❌ Файл слишком большой. Максимальный размер — 50 МБ.');
        } else if (res.status === 415 || res.status === 400) {
          const err = await res.json().catch(() => ({}));
          setStatus(`❌ ${err.error || 'Недопустимый формат файла. Загрузите PDF.'}`);
        } else if (res.status === 401 || res.status === 403) {
          setStatus('❌ Ошибка авторизации. Перезапустите приложение.');
        } else if (res.status >= 500) {
          setStatus('❌ Ошибка сервера. Попробуйте чуть позже.');
        } else {
          const err = await res.json().catch(() => ({}));
          setStatus(`❌ ${err.error || `Ошибка ${res.status}`}`);
        }
      }
    } catch { setStatus('❌ Нет соединения с сервером. Проверьте интернет.'); }
    finally { setUploading(false); }
  };

  // --- Helpers ---
  const autoStatusLabel = (s) => {
    if (s === 'PROCESSING') return '⏳ На авто-проверке';
    if (s === 'SUCCESS')    return '✅ Оформление ОК';
    if (s === 'REJECTED')   return '❌ Ошибки оформления';
    return s;
  };

  const verdictLabel = (v) => {
    if (v === 'APPROVED') return '✅ Принято преподавателем';
    if (v === 'REVISION')  return '🔄 На доработке';
    return null;
  };

  const formatDate = (iso) => {
    if (!iso) return '';
    const d = new Date(iso);
    return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' })
      + ' ' + d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  };

  const handleTabChange = (t) => { setDirection(t > activeTab ? 1 : -1); setActiveTab(t); setStatus(''); };

  const startTutorial = () => {
    setDirection(0 > activeTab ? 1 : -1);
    setActiveTab(0);
    setTutorialStep(0);
    setTutorialActive(true);
  };

  const handleRoleSelect = (role) => {
    setUserRole(role); setRegInput(''); setRegCode(''); setRegError('');
    setTeacherEmail(''); setStep('register');
  };

  const handleExistingRoleLogin = async (role) => {
    setRegError('');
    try {
      const res = await fetch(`${API_BASE}/me`, { headers: apiHeaders() });
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
      setCurrentUserId(data.telegram_id ? String(data.telegram_id) : currentUserId);
      setUserRole(role);
      setRegisteredRole(role);
      setDirection(0);
      setActiveTab(0);
      setStep('main');
      if (role === 'student') { fetchTeachers(); fetchSubmissions(); }
      else { fetchTeacherSubmissions(); fetchTeacherHistory(); }
    } catch {
      setRegError('Сервер недоступен');
    }
  };

  const resetRegistration = () => {
    setStep('role'); setUserRole(''); setRegInput(''); setRegCode('');
    setRegError(''); setTeacherEmail('');
  };

  const variants = {
    enter:  (d) => ({ x: d > 0 ? 300 : -300, opacity: 0 }),
    center: { x: 0, opacity: 1 },
    exit:   (d) => ({ x: d < 0 ? 300 : -300, opacity: 0 }),
  };

  // --- Структура вкладок ---
  const studentTabs = [
    { icon: '📁', label: 'Загрузка' },
    { icon: '🔔', label: 'Работы',    ref: refs.navNotif },
    { icon: '⚙️', label: 'Настройки', ref: refs.navSettings },
  ];
  const teacherTabs = [
    { icon: '📋', label: 'Работы' },
    { icon: '📚', label: 'История',   ref: refs.navHistory },
    { icon: '📝', label: 'Заметки',   ref: refs.navNotes },
    { icon: '⚙️', label: 'Настройки', ref: refs.navSettings },
  ];
  const tabs = userRole === 'teacher' ? teacherTabs : studentTabs;

  const settingsTabIndex = userRole === 'teacher' ? 3 : 2;

  return (
    <div className={`App ${resolvedTheme === 'dark' ? 'app-dark' : ''} font-${fontSize} ${contrast ? 'app-contrast' : ''}`}>

      {/* Модальное окно авторов */}
      {showAuthors && <AuthorsModal onClose={() => setShowAuthors(false)} />}

      {/* Диалог подтверждения отзыва работы */}
      {withdrawConfirmId !== null && (
        <div className="confirm-overlay" onClick={() => setWithdrawConfirmId(null)}>
          <div className="confirm-dialog" onClick={e => e.stopPropagation()}>
            <p className="confirm-title">Отозвать работу?</p>
            <p className="confirm-body">Работа исчезнет из списка преподавателя, а уведомление в Telegram будет удалено.</p>
            <div className="confirm-btns">
              <button className="secondary-btn" onClick={() => setWithdrawConfirmId(null)}>Отмена</button>
              <button className="withdraw-confirm-btn" onClick={confirmWithdraw}>Отозвать</button>
            </div>
          </div>
        </div>
      )}

      {/* Кнопка туториала */}
      {step === 'main' && (
        <button className="tutorial-btn" onClick={startTutorial} title="Справка">
          💡
        </button>
      )}

      {/* Туториал */}
      {tutorialActive && (
        <TutorialOverlay
          steps={userRole === 'teacher' ? TEACHER_STEPS : STUDENT_STEPS}
          step={tutorialStep}
          onNext={handleTutorialNext}
          onSkip={handleTutorialSkip}
          refs={refs}
        />
      )}

      {step !== 'main' && (
        <div className="branding fade-in">
          <img src="cat.jpg" alt="Logo" className="logo" />
          <h1>SAPSR</h1>
        </div>
      )}

      <AnimatePresence custom={direction} mode="wait">
        {step === 'loading' && (
          <MotionDiv key="loading" className="screen" exit={{ opacity: 0 }}>
            <p className="description">Загрузка...</p>
          </MotionDiv>
        )}

        {step === 'role' && (
          <MotionDiv key="role" className="screen" exit={{ opacity: 0 }}>
            <p className="description">Выберите вашу роль в системе</p>
            <div className="role-container">
              {[
                ['student', '👨‍🎓', 'Студент'],
                ['teacher', '👨‍🏫', 'Преподаватель'],
              ].map(([role, icon, label]) => (
                <div className="role-row" key={role}>
                  <button className="role-card" onClick={() => handleRoleSelect(role)}>
                    <span className="role-icon">{icon}</span>
                    <span className="role-text">{label}</span>
                  </button>
                  {registeredRole === role && (
                    <button className="role-login-btn" onClick={() => handleExistingRoleLogin(role)}>
                      Войти
                    </button>
                  )}
                </div>
              ))}
            </div>
            {regError && <div className="reg-error role-error">{regError}</div>}
          </MotionDiv>
        )}

        {step === 'register' && userRole === 'student' && (
          <MotionDiv key="reg-student" className="screen" exit={{ opacity: 0 }}>
            <p className="description">Введите ФИО и номер группы</p>
            <form onSubmit={handleRegisterStudent} className="register-form">
              <input type="text" className="reg-input" placeholder="Иванов И.О., 321702"
                value={regInput} onChange={(e) => setRegInput(e.target.value.replace(STUDENT_INPUT_ALLOWED_RE, ''))} autoFocus />
              <p className="reg-hint">Формат: Иванов И.И., 321702. Только кириллица, точки и запятая.</p>
              {regError && <div className="reg-error">{regError}</div>}
              <div className="vertical-button-group">
                <button type="submit" className="submit-btn" disabled={registering}>
                  {registering ? '⏳ Регистрация...' : '✅ Зарегистрироваться'}
                </button>
                <button type="button" className="secondary-btn" onClick={() => setStep('role')}>⬅️ Назад</button>
              </div>
            </form>
          </MotionDiv>
        )}

        {step === 'register' && userRole === 'teacher' && (
          <MotionDiv key="reg-teacher" className="screen" exit={{ opacity: 0 }}>
            <p className="description">Регистрация преподавателя</p>
            <form onSubmit={handleTeacherSendCode} className="register-form">
              <input type="email" className="reg-input" placeholder="ivanov@bsuir.by"
                value={teacherEmail} onChange={(e) => setTeacherEmail(e.target.value)} autoFocus />
              <p className="reg-hint">Введите корпоративную почту @bsuir.by. ФИО будет заполнено автоматически из IIS.</p>
              {regError && <div className="reg-error">{regError}</div>}
              <div className="vertical-button-group">
                <button type="submit" className="submit-btn" disabled={sendingCode}>
                  {sendingCode ? '⏳ Отправка...' : '📧 Отправить код'}
                </button>
                <button type="button" className="secondary-btn" onClick={() => setStep('role')}>⬅️ Назад</button>
              </div>
            </form>
          </MotionDiv>
        )}

        {step === 'confirm_code' && (
          <MotionDiv key="confirm-code" className="screen" exit={{ opacity: 0 }}>
            <p className="description">Код отправлен на<br /><b>{teacherEmail}</b></p>
            <p className="reg-hint">Проверьте папку «Спам», если письмо не пришло</p>
            <form onSubmit={handleConfirmCode} className="register-form">
              <input type="text" className="reg-input code-input-wide" placeholder="6-значный код"
                value={regCode} onChange={(e) => setRegCode(e.target.value)} maxLength={6} inputMode="numeric" autoFocus />
              <p className={`code-timer ${codeTimeLeft <= 0 ? 'code-timer-expired' : ''}`}>
                {codeTimeLeft > 0
                  ? `Код действителен ещё ${formatCountdown(codeTimeLeft)}`
                  : 'Срок действия кода истёк'}
              </p>
              {regError && <div className="reg-error">{regError}</div>}
              <div className="vertical-button-group">
                <button type="submit" className="submit-btn" disabled={registering || codeTimeLeft <= 0}>
                  {registering ? '⏳ Проверка...' : '✅ Подтвердить'}
                </button>
                <button type="button" className="secondary-btn" disabled={sendingCode} onClick={handleResendCode}>
                  {sendingCode ? '⏳ Отправка...' : '🔄 Отправить код повторно'}
                </button>
                <button type="button" className="secondary-btn" onClick={() => {
                  setStep('register'); setRegError(''); setRegCode(''); setCodeExpiresAt(null); setCodeTimeLeft(0);
                }}>
                  ⬅️ Назад
                </button>
              </div>
            </form>
          </MotionDiv>
        )}

        {step === 'main' && (
          <MotionDiv key={activeTab} custom={direction} variants={variants}
            initial="enter" animate="center" exit="exit"
            transition={{ type: 'spring', stiffness: 520, damping: 36, mass: 0.75 }}
            className="screen main-content"
          >
            {/* ===== СТУДЕНТ: загрузка ===== */}
            {userRole === 'student' && activeTab === 0 && (
              <div className="tab-view">
                <h2 className="view-title">Загрузка работы</h2>
                <div className="upload-container">
                  <form onSubmit={handleSubmitFile}>
                    {teachers.length > 0 && (
                      <div className="teacher-select-wrapper" ref={refs.teacherSel}>
                        <label htmlFor="teacher-select" className="select-label">Преподаватель</label>
                        <select id="teacher-select" className="teacher-select"
                          value={selectedTeacherId} onChange={(e) => {
                            setSelectedTeacherId(e.target.value);
                            localStorage.setItem(LAST_TEACHER_KEY, e.target.value);
                          }}>
                          {teachers.map((t) => (
                            <option key={t.telegram_id || t.id} value={t.telegram_id || t.id}>
                              {t.full_name || t.name}
                            </option>
                          ))}
                        </select>
                      </div>
                    )}
                    <label htmlFor="file-upload" className="custom-file-upload" ref={refs.fileUpload}
                      title={file ? file.name : 'Нажмите, чтобы выбрать файл (.pdf)'}>
                      <span style={{ fontSize: 'calc(var(--app-font-size) * 2.15)' }}>📁</span>
                      <span>{file ? file.name : 'Нажмите, чтобы выбрать файл (.pdf)'}</span>
                    </label>
                    <input id="file-upload" type="file" accept=".pdf"
                      onChange={(e) => { setFile(e.target.files[0]); setStatus(''); }} />
                    {teachers.length === 0 && (
                      <p className="reg-hint" style={{ color: '#e53935' }}>Нет доступных преподавателей. Попробуйте позже.</p>
                    )}
                    <button type="submit" className="submit-btn" ref={refs.submitBtn}
                      disabled={!file || uploading || teachers.length === 0 || !selectedTeacherId}
                      style={{ marginTop: '20px' }}>
                      {uploading ? '⏳ Отправка...' : 'Отправить'}
                    </button>
                  </form>
                </div>
                {status && <div className="status-msg">{status}</div>}
              </div>
            )}

            {/* ===== СТУДЕНТ: мои работы ===== */}
            {userRole === 'student' && activeTab === 1 && (
              <div className="tab-view">
                <h2 className="view-title">Мои работы</h2>
                <button className="refresh-btn" onClick={fetchSubmissions}>🔄 Обновить</button>
                <div className="notif-window student-works-window">
                  {submissions.length === 0 && <p className="notif-empty">Пока нет загруженных файлов</p>}
                  {studentWorkGroups.map(group => {
                    const s = group.latest;
                    return (
                      <div key={group.key} className={`notif-line notif-line-stacked ${s.status === 'REJECTED' ? 'notif-error' : ''} ${s.status === 'SUCCESS' ? 'notif-success' : ''}`}>
                        <div className="notif-row-main">
                          <div className="notif-info">
                            <div className="notif-file-subject"><b>{group.fileName}</b></div>
                            <div className="iteration-chain">
                              {group.timeline.map((item, index) => (
                                <span key={item.id} className="iteration-step">
                                  v{index + 1} {iterationMark(item)}
                                </span>
                              ))}
                              {s.teacher_verdict === 'APPROVED' && <span className="iteration-final">Принято преподавателем</span>}
                            </div>
                            <div className="notif-status">{autoStatusLabel(s.status)}</div>
                            {s.teacher_verdict && (
                              <div className={`notif-verdict ${s.teacher_verdict === 'APPROVED' ? 'verdict-ok' : 'verdict-revision'}`}>
                                {verdictLabel(s.teacher_verdict)}
                              </div>
                            )}
                            {s.teacher_comment && <div className="notif-comment">💬 {s.teacher_comment}</div>}
                          </div>
                          <div className="notif-btn-group">
                            {s.status !== 'PROCESSING' && (
                              <button className="download-btn" title="Скачать отчёт" onClick={() => handleDownloadReport(s.id)}>📥</button>
                            )}
                            {s.status === 'SUCCESS' && !s.teacher_verdict && (
                              <button className="withdraw-btn" aria-label="Отозвать работу" title="Отозвать работу" disabled={withdrawing === s.id}
                                onClick={() => handleWithdrawWork(s.id)}>
                                {withdrawing === s.id ? '⏳' : '↩'}
                              </button>
                            )}
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* ===== ПРЕПОДАВАТЕЛЬ: ожидающие работы ===== */}
            {userRole === 'teacher' && activeTab === 0 && (
              <div className="tab-view">
                <h2 className="view-title">Работы студентов</h2>
                <button className="refresh-btn" onClick={fetchTeacherSubmissions}>🔄 Обновить</button>
                <div className="notif-window" ref={refs.submissions}>
                  {teacherSubmissions.length === 0 && <p className="notif-empty">Нет работ, ожидающих проверки</p>}
                  {teacherSubmissions.length > 0 && filteredTeacherSubmissions.length === 0 && (
                    <p className="notif-empty">По этому запросу работ не найдено</p>
                  )}
                  {filteredTeacherSubmissions.map(s => {
                    const isExpanded = expandedId === s.id;
                    return (
                      <div key={s.id} className={`ts-card-compact ${isExpanded ? 'ts-card-expanded' : ''}`}>
                        <div className="ts-card-main" onClick={() => setExpandedId(isExpanded ? null : s.id)}>
                          <div className="ts-row-top">
                            <span className="ts-student">{s.student_name || 'Студент'}</span>
                            <span className="ts-date">{formatDate(s.created_at)}</span>
                          </div>
                          <div className="ts-row-bottom">
                            <span className="ts-file">{s.file_name}</span>
                            <span className="ts-chevron">{isExpanded ? '▲' : '▼'}</span>
                          </div>
                        </div>
                        {isExpanded && (
                          <div className="ts-actions">
                            <button className="download-btn-sm" onClick={() => handleDownloadPdf(s.id)}>📄 Скачать PDF</button>
                            <button className="approve-btn" disabled={verdictLoading}
                              onClick={() => handleVerdict(s.id, 'APPROVED')}>
                              ✅ Принять
                            </button>
                            {revisionId === s.id ? (
                              <div className="revision-form">
                                <textarea className="revision-input" rows={3}
                                  placeholder="Комментарий для студента..."
                                  value={revisionComment} onChange={(e) => setRevisionComment(e.target.value)} />
                                {revisionSuggestions.length > 0 && (
                                  <div className="comment-suggestions">
                                    <div className="comment-suggestions-title">Шаблонные ответы</div>
                                    {revisionSuggestions.map((template) => (
                                      <button key={template.text} type="button" className="comment-suggestion"
                                        onClick={() => setRevisionComment(template.text)}>
                                        <span>{template.text}</span>
                                      </button>
                                    ))}
                                  </div>
                                )}
                                <div className="revision-btns">
                                  <button className="submit-btn" disabled={!revisionComment.trim() || verdictLoading}
                                    onClick={() => handleVerdict(s.id, 'REVISION', revisionComment)}>
                                    {verdictLoading ? '⏳' : 'Отправить'}
                                  </button>
                                  <button className="secondary-btn" onClick={() => { setRevisionId(null); setRevisionComment(''); }}>
                                    Отмена
                                  </button>
                                </div>
                              </div>
                            ) : (
                              <button className="revision-btn" onClick={() => { setRevisionId(s.id); setRevisionComment(''); }}>
                                🔄 На доработку
                              </button>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
                <input className="teacher-search" type="search"
                  placeholder="Поиск по группе, студенту или файлу..."
                  ref={refs.teacherSearch}
                  value={teacherSearch} onChange={(e) => setTeacherSearch(e.target.value)} />
              </div>
            )}

            {/* ===== ПРЕПОДАВАТЕЛЬ: история проверок ===== */}
            {userRole === 'teacher' && activeTab === 1 && (
              <div className="tab-view">
                <h2 className="view-title">История проверок</h2>
                <button className="refresh-btn" onClick={fetchTeacherHistory}>🔄 Обновить</button>
                <div className="notif-window" style={{ height: '65vh' }}>
                  {teacherHistory.length === 0 && (
                    <p className="notif-empty">История проверок пуста</p>
                  )}
                  {teacherHistory.map(s => (
                    <div key={s.id} className="ts-card-compact history-card">
                      <div className="ts-card-main" style={{ cursor: 'default' }}>
                        <div className="ts-row-top">
                          <span className="ts-student">{s.student_name || 'Студент'}</span>
                          <span className={`history-verdict-badge ${s.teacher_verdict === 'APPROVED' ? 'history-badge-ok' : 'history-badge-revision'}`}>
                            {s.teacher_verdict === 'APPROVED' ? '✅ Принято' : '🔄 Доработка'}
                          </span>
                        </div>
                        <div className="ts-row-bottom">
                          <span className="ts-file">{s.file_name}</span>
                          <span className="ts-date">{formatDate(s.created_at)}</span>
                        </div>
                        {s.teacher_comment && (
                          <div className="ts-comment">💬 {s.teacher_comment}</div>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* ===== ПРЕПОДАВАТЕЛЬ: заметки ===== */}
            {userRole === 'teacher' && activeTab === 2 && (
              <div className="tab-view">
                <h2 className="view-title">Заметки</h2>
                <button className="add-note-btn" ref={refs.addNoteBtn} onClick={() => { setNoteInput(''); setEditingNoteId('new'); }}>
                  + Новая заметка
                </button>
                {editingNoteId === 'new' && (
                  <div className="note-editor">
                    <textarea className="note-textarea" value={noteInput}
                      onChange={e => setNoteInput(e.target.value)}
                      placeholder="Введите заметку..." autoFocus />
                    <div className="note-editor-btns">
                      <button className="submit-btn" onClick={handleSaveNote}>Сохранить</button>
                      <button className="secondary-btn" onClick={() => setEditingNoteId(null)}>Отмена</button>
                    </div>
                  </div>
                )}
                <div className="notes-list">
                  {teacherNotes.map(note => (
                    <div key={note.id} className="note-card">
                      {editingNoteId === note.id ? (
                        <div className="note-editor">
                          <textarea className="note-textarea" value={noteInput}
                            onChange={e => setNoteInput(e.target.value)} autoFocus />
                          <div className="note-editor-btns">
                            <button className="submit-btn" onClick={() => handleUpdateNote(note.id)}>Сохранить</button>
                            <button className="secondary-btn" onClick={() => setEditingNoteId(null)}>Отмена</button>
                          </div>
                        </div>
                      ) : (
                        <>
                          <p className="note-text">{note.text}</p>
                          <div className="note-actions">
                            <button className="note-action-btn note-action-icon" title="Изменить"
                              onClick={() => { setEditingNoteId(note.id); setNoteInput(note.text); }}>✏️</button>
                            <button className="note-action-btn note-action-delete note-action-icon" title="Удалить"
                              onClick={() => handleDeleteNote(note.id)}>🗑</button>
                            <button className="note-action-btn note-action-find note-action-icon" title="Найти студента"
                              onClick={() => handleFindFromNote(note.text)}>🔍</button>
                          </div>
                        </>
                      )}
                    </div>
                  ))}
                  {teacherNotes.length === 0 && !editingNoteId && (
                    <p className="notif-empty">Нет заметок. Нажмите «+ Новая заметка»</p>
                  )}
                </div>
              </div>
            )}

            {/* ===== НАСТРОЙКИ (общие) ===== */}
            {activeTab === settingsTabIndex && (
              <div className="tab-view">
                <h2 className="view-title">Настройки</h2>

                <div className="settings-section">
                  <div className="settings-label">Тема оформления</div>
                  <div className="settings-row">
                    <span className="settings-desc">Ночной режим</span>
                    <button className={`toggle-switch ${theme === 'dark' ? 'on' : ''}`}
                      onClick={() => setTheme(t => t === 'dark' ? 'light' : 'dark')}
                      aria-label="Переключить ночной режим">
                      <span className="toggle-thumb" />
                    </button>
                  </div>
                  <div className="settings-row settings-row-spaced">
                    <span className="settings-desc">Контрастный режим</span>
                    <button className={`toggle-switch ${contrast ? 'on' : ''}`}
                      onClick={() => setContrast(c => !c)}
                      aria-label="Переключить контрастный режим">
                      <span className="toggle-thumb" />
                    </button>
                  </div>
                </div>

                <div className="settings-section">
                  <div className="settings-label">Размер шрифта</div>
                  <div className="fontsize-slider-row">
                    <span className="fontsize-marker">A−</span>
                    <input
                      className="fontsize-slider"
                      type="range"
                      min="0"
                      max={FONT_SIZE_OPTIONS.length - 1}
                      step="1"
                      value={Math.max(0, FONT_SIZE_OPTIONS.indexOf(fontSize))}
                      onChange={(e) => setFontSize(FONT_SIZE_OPTIONS[Number(e.target.value)] || 'normal')}
                      aria-label="Размер шрифта"
                    />
                    <span className="fontsize-marker fontsize-marker-large">A+</span>
                  </div>
                  <div className="settings-desc">{FONT_SIZE_LABELS[fontSize] || FONT_SIZE_LABELS.normal}</div>
                </div>

                <div className="settings-divider" />
                <button className="secondary-btn settings-wide-btn" onClick={resetRegistration}>Выйти из системы</button>
              </div>
            )}
          </MotionDiv>
        )}
      </AnimatePresence>

      {step === 'main' && (
        <div className="nav-wrapper">
          <div className="bottom-nav">
            {tabs.map((tab, i) => (
              <button key={i} ref={tab.ref || null}
                className={activeTab === i ? 'active' : ''}
                onClick={() => handleTabChange(i)}>
                <div className="nav-icon-bg">
                  {tab.icon}
                  {userRole === 'teacher' && i === 0 && teacherSubmissions.length > 0 && (
                    <span className="nav-badge">{teacherSubmissions.length}</span>
                  )}
                </div>
              </button>
            ))}
          </div>
          <button className="authors-link" onClick={() => setShowAuthors(true)}>Авторы</button>
        </div>
      )}
    </div>
  );
}

export default App;
