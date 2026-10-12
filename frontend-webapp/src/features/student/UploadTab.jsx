export default function UploadTab({ upload, teacherSelectRef, fileUploadRef, submitRef }) {
  const { file, status, teachers, selectedTeacherId, uploading, selectTeacher, selectFile, submit } = upload;

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
          <label htmlFor="file-upload" className="custom-file-upload" ref={fileUploadRef}
            title={file ? file.name : 'Нажмите, чтобы выбрать файл (.pdf)'}>
            <span style={{ fontSize: 'calc(var(--app-font-size) * 2.15)' }}>📁</span>
            <span>{file ? file.name : 'Нажмите, чтобы выбрать файл (.pdf)'}</span>
          </label>
          <input id="file-upload" type="file" accept=".pdf"
            onChange={(e) => selectFile(e.target.files[0])} />
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
