import AppHeader from '../../components/AppHeader';
import Icon from '../../components/Icon';
import StatusLine from '../../components/StatusLine';
import PrimaryAction from '../../components/PrimaryAction';
import { WORK_TYPES } from '../../lib/workTypes';
import { formatSize } from '../../lib/format';
import TermInfo from './TermInfo';
import { useUpload } from './useUpload';

// Сдача работы: преподаватель, вид работы, файл. Курс и семестр сервер определяет сам — здесь они только видны.
export default function UploadScreen({ me, preset, header, onUploaded }) {
  const upload = useUpload({ active: true, preset, onUploaded });
  const {
    teachers, teachersLoaded, teacherId, workType, file, problem, ready, uploading, error,
    selectTeacher, setWorkType, selectFile, submit,
  } = upload;
  const noTeachers = teachersLoaded && teachers.length === 0;

  return (
    <form className="screen-body" onSubmit={submit}>
      <AppHeader title={preset ? 'Новая версия' : 'Сдать работу'} {...header} />
      <TermInfo me={me} />

      <div className="field">
        <label className="field-label" htmlFor="teacher-select">Преподаватель</label>
        {noTeachers ? (
          <p className="field-error">
            Для группы {me?.group || ''} нет преподавателей. Сообщите администратору.
          </p>
        ) : (
          <select id="teacher-select" className="input" value={teacherId} disabled={!teachersLoaded}
            onChange={(e) => selectTeacher(e.target.value)}>
            <option value="">{teachersLoaded ? 'Выберите преподавателя' : 'Загрузка…'}</option>
            {teachers.map((t) => <option key={t.telegram_id} value={t.telegram_id}>{t.full_name}</option>)}
          </select>
        )}
      </div>

      <div className="field">
        <label className="field-label" htmlFor="work-type-select">Вид работы</label>
        <select id="work-type-select" className="input" value={workType} onChange={(e) => setWorkType(e.target.value)}>
          <option value="">Выберите вид работы</option>
          {WORK_TYPES.map(({ value, label }) => <option key={value} value={value}>{label}</option>)}
        </select>
      </div>

      <div className="field">
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
              <span>Выберите PDF</span>
              <span className="hint">до 50 МБ</span>
            </span>
          )}
        </label>
        <input id="file-upload" className="visually-hidden" type="file" accept=".pdf,application/pdf"
          onChange={(e) => { selectFile(e.target.files[0]); e.target.value = ''; }} />
        {problem && <p className="field-error">{problem}</p>}
      </div>

      {error && <StatusLine tone="error" className="form-status">{error}</StatusLine>}

      <PrimaryAction text="Отправить на проверку" progressText="Отправка…" submit onClick={submit}
        disabled={!ready} progress={uploading} />
    </form>
  );
}
