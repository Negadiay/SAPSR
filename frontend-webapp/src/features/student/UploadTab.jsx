import { WORK_TYPES, workFileName } from '../../lib/workName';
import { lastNameOf } from '../../lib/names';

const SEMESTERS = [1, 2, 3, 4, 5, 6, 7, 8];
const STAGES = [[1, '1-я опроцентовка'], [2, '2-я опроцентовка'], [3, '3-я опроцентовка']];

// Вид работы, семестр и этап определяют, какие требования методички проверяются (api-v1.md, раздел 7).
function ProfileSelects({ profile, setProfileField }) {
  return (
    <div className="upload-profile">
      <label className="select-label" htmlFor="work-type-select">Вид работы</label>
      <select id="work-type-select" className="teacher-select" value={profile.work_type}
        onChange={(e) => setProfileField('work_type', e.target.value)}>
        <option value="">Не указан</option>
        {WORK_TYPES.map(({ value, label }) => <option key={value} value={value}>{label}</option>)}
      </select>
      <div className="upload-profile-row">
        <div>
          <label className="select-label" htmlFor="semester-select">Семестр</label>
          <select id="semester-select" className="teacher-select" value={profile.semester}
            onChange={(e) => setProfileField('semester', e.target.value)}>
            <option value="">—</option>
            {SEMESTERS.map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
        <div>
          <label className="select-label" htmlFor="stage-select">Этап</label>
          <select id="stage-select" className="teacher-select" value={profile.stage}
            onChange={(e) => setProfileField('stage', e.target.value)}>
            <option value="">Итоговая сдача</option>
            {STAGES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </div>
      </div>
      {!profile.semester && (
        <p className="reg-hint">Без семестра проверяются только оформление и согласованность текста.</p>
      )}
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
    workType: profile.work_type, semester: profile.semester, group: me?.group, lastName: lastNameOf(me),
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
          <ProfileSelects profile={profile} setProfileField={setProfileField} />
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
