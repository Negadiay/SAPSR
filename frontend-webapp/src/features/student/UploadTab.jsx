import { WORK_TYPES, workFileName } from '../../lib/workName';
import { lastNameOf } from '../../lib/names';

const STAGE_DATIVE = { 1: '1-й', 2: '2-й', 3: '3-й' };

// Курс, семестр и этап студент не выбирает: их определяет сервер по группе и учебному календарю
// (решение 11), а ошибки исправляет администратор. От них зависят требования методички.
function TermLine({ me }) {
  if (!me) return null;
  if (!me.semester) {
    return (
      <p className="reg-hint upload-term">
        Семестр по группе {me.group || '—'} не определился — проверятся только оформление и согласованность
        текста. Если это ошибка, сообщите администратору.
      </p>
    );
  }
  return (
    <div className="upload-term">
      <span className="upload-term-main">{me.course} курс · {me.semester} семестр</span>
      <span className="upload-term-stage">
        {me.stage ? `проверка к ${STAGE_DATIVE[me.stage]} опроцентовке` : 'итоговая сдача'}
      </span>
    </div>
  );
}

// Вид работы по номеру группы не узнать — его выбирает студент (api-v1.md, раздел 7).
function ProfileSelects({ profile, setProfileField, me }) {
  return (
    <div className="upload-profile">
      <label className="select-label" htmlFor="work-type-select">Вид работы</label>
      <select id="work-type-select" className="teacher-select" value={profile.work_type}
        onChange={(e) => setProfileField('work_type', e.target.value)}>
        <option value="">Не указан</option>
        {WORK_TYPES.map(({ value, label }) => <option key={value} value={value}>{label}</option>)}
      </select>
      <TermLine me={me} />
    </div>
  );
}

// me — свои данные из GET /me: группа и фамилия для имени файла по форме.
export default function UploadTab({ upload, me, teacherSelectRef, fileUploadRef, submitRef }) {
  const {
    file, status, teachers, selectedTeacherId, uploading, profile,
    selectTeacher, selectFile, setProfileField, submit,
  } = upload;
  // Имя по форме нужно преподавателю, чтобы работы не путались у него на диске.
  const expectedName = workFileName({
    workType: profile.work_type, semester: me?.semester, group: me?.group, lastName: lastNameOf(me),
  });
  const placeholder = expectedName ? `Загрузите документ с названием ${expectedName}` : 'Нажмите, чтобы выбрать файл (.pdf)';
  const nameDiffers = file && expectedName && !expectedName.includes('<')
    && file.name.toLowerCase() !== expectedName.toLowerCase();

  return (
    <div className="tab-view">
      <h2 className="view-title">Загрузка работы</h2>
      <div className="upload-container">
        <form onSubmit={submit}>
          {teachers.length > 0 && (
            <div className="teacher-select-wrapper" ref={teacherSelectRef}>
              <label htmlFor="teacher-select" className="select-label">Преподаватель</label>
              <select id="teacher-select" className="teacher-select"
                value={selectedTeacherId} onChange={(e) => selectTeacher(e.target.value)}>
                {teachers.map((t) => (
                  <option key={t.telegram_id || t.id} value={t.telegram_id || t.id}>
                    {t.full_name || t.name}
                  </option>
                ))}
              </select>
            </div>
          )}
          <ProfileSelects profile={profile} setProfileField={setProfileField} me={me} />
          <label htmlFor="file-upload" className="custom-file-upload" ref={fileUploadRef}
            title={file ? file.name : placeholder}>
            <span style={{ fontSize: 'calc(var(--app-font-size) * 2.15)' }}>📁</span>
            <span className="upload-file-name">{file ? file.name : placeholder}</span>
          </label>
          <input id="file-upload" type="file" accept=".pdf"
            onChange={(e) => selectFile(e.target.files[0])} />
          {nameDiffers && <p className="reg-hint">Название по форме: {expectedName}</p>}
          {teachers.length === 0 && (
            <p className="reg-hint" style={{ color: '#e53935' }}>Нет доступных преподавателей. Попробуйте позже.</p>
          )}
          <button type="submit" className="submit-btn" ref={submitRef}
            disabled={!file || uploading || teachers.length === 0 || !selectedTeacherId}
            style={{ marginTop: '20px' }}>
            {uploading ? '⏳ Отправка...' : 'Отправить'}
          </button>
        </form>
      </div>
      {status && <div className="status-msg">{status}</div>}
    </div>
  );
}
