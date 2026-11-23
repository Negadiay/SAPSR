import { useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import AppHeader from '../../components/AppHeader';
import Icon from '../../components/Icon';
import StatusLine from '../../components/StatusLine';
import PrimaryAction from '../../components/PrimaryAction';
import { formatSize } from '../../lib/format';
import TermInfo from './TermInfo';
import { teacherKey, useUpload } from './useUpload';

// Файл нельзя выбрать, пока неизвестно, кому его отправить: вместо выбора файла — это окно.
function TeacherPrompt({ teachers, group, onChoose, onClose }) {
  const none = teachers.length === 0;
  return (
    <div className="dialog-overlay" onClick={onClose}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Преподаватель не выбран"
        onClick={(e) => e.stopPropagation()}>
        <p className="dialog-title">Преподаватель не выбран</p>
        <p className="dialog-body">
          {none ? `Для группы ${group || ''} пока нет преподавателей. Сообщите администратору.` : 'Кому отправить работу?'}
        </p>
        {!none && (
          <div className="dialog-choices">
            {teachers.map((t) => (
              <button key={teacherKey(t)} type="button" className="btn btn-secondary btn-block"
                onClick={() => onChoose(teacherKey(t))}>
                {t.full_name}
              </button>
            ))}
          </div>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn-secondary" onClick={onClose}>{none ? 'Понятно' : 'Отмена'}</button>
        </div>
      </div>
    </div>
  );
}

// Сдача работы: преподаватель и файл. Курс и семестр сервер определяет сам — здесь они только видны;
// вид работы — по семестру и титульному листу, поэтому его не спрашиваем.
export default function UploadScreen({ me, preset, header, onUploaded }) {
  const upload = useUpload({ active: true, preset, onUploaded, maxUploadMb: me?.max_upload_mb });
  const {
    teachers, teachersLoaded, teacherId, file, problem, ready, uploading, error, maxMb,
    selectTeacher, selectFile, submit,
  } = upload;
  const noTeachers = teachersLoaded && teachers.length === 0;
  const [askTeacher, setAskTeacher] = useState(false);
  const fileInput = useRef(null);

  const guardFilePicker = (e) => {
    if (teacherId) return;
    e.preventDefault();
    if (teachersLoaded) setAskTeacher(true);
  };

  // Преподаватель выбран в окне — сразу открываем выбор файла, ради которого студент нажимал.
  const chooseTeacher = (id) => {
    flushSync(() => {
      selectTeacher(id);
      setAskTeacher(false);
    });
    fileInput.current?.click();
  };

  return (
    <form className="screen-body" onSubmit={submit}>
      <AppHeader title={preset ? 'Новая версия' : 'Сдать работу'} {...header} />
      <TermInfo me={me} />
      {me?.cloud_check && (
        <p className="cloud-note">
          <Icon name="cloud" />
          <span>Сейчас работы проверяет облачный сервис. Титульный лист и личные данные ему не передаются.</span>
        </p>
      )}

      <div className="field" data-tour="upload-teacher">
        <label className="field-label" htmlFor="teacher-select">Преподаватель</label>
        {noTeachers ? (
          <p className="field-error">
            Для группы {me?.group || ''} нет преподавателей. Сообщите администратору.
          </p>
        ) : (
          <select id="teacher-select" className="input" value={teacherId} disabled={!teachersLoaded}
            onChange={(e) => selectTeacher(e.target.value)}>
            {/* Подсказка — только пока преподаватель не выбран: вернуться к «никому» нельзя */}
            {!teacherId && <option value="" disabled>{teachersLoaded ? 'Выберите преподавателя' : 'Загрузка…'}</option>}
            {teachers.map((t) => <option key={teacherKey(t)} value={teacherKey(t)}>{t.full_name}</option>)}
          </select>
        )}
      </div>

      <div className="field" data-tour="upload-file">
        <span className="field-label">Файл</span>
        <label htmlFor="file-upload" className={`file-drop ${file ? 'file-drop-chosen' : ''}`}>
          <Icon name={file ? 'file' : 'upload'} className="file-drop-icon" />
          {file ? (
            <span className="file-drop-text">
              <span className="file-drop-name">{file.name}</span>
              <span className="hint">{formatSize(file.size)} · нажмите, чтобы заменить</span>
            </span>
          ) : (
            <span className="file-drop-text">
              <span>Выберите PDF или DOCX</span>
              <span className="hint">до {maxMb} МБ</span>
            </span>
          )}
        </label>
        <input id="file-upload" ref={fileInput} className="visually-hidden" type="file"
          accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
          onClick={guardFilePicker}
          onChange={(e) => { selectFile(e.target.files[0]); e.target.value = ''; }} />
        {problem && <p className="field-error">{problem}</p>}
      </div>

      {error && <StatusLine tone="error" className="form-status">{error}</StatusLine>}

      <PrimaryAction text="Отправить на проверку" progressText="Отправка…" submit onClick={submit}
        disabled={!ready} progress={uploading} />

      {askTeacher && (
        <TeacherPrompt teachers={teachers} group={me?.group} onChoose={chooseTeacher}
          onClose={() => setAskTeacher(false)} />
      )}
    </form>
  );
}
