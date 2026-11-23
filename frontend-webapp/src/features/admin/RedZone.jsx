import { useState } from 'react';
import Icon from '../../components/Icon';
import StatusLine from '../../components/StatusLine';
import ConfirmDialog from '../../components/ConfirmDialog';
import { count, formatDate } from '../../lib/format';

// «Красная зона» (решение 13): то, что меняет работу всей системы сразу, — модель, которая проверяет работы.
// Список моделей присылает обработчик: адреса и ключи задаёт только тот, кто разворачивает систему.

const MODES = { hybrid: 'правила и модель', symbolic: 'только правила', neural: 'только модель (сравнение)' };

const LLM_MODES = { replay: 'записанные ответы', off: 'выключена', stub: 'тестовая', record: 'запись ответов' };

const ago = (seconds) => {
  if (seconds < 60) return 'только что';
  const minutes = Math.round(seconds / 60);
  return minutes < 60 ? `${count(minutes, 'минуту', 'минуты', 'минут')} назад` : 'больше часа назад';
};

function WorkerCard({ worker }) {
  if (!worker.known) {
    return (
      <section className="card redzone-worker">
        <StatusLine tone="muted">Сервис проверки ещё не подключился. Обычно это занимает около минуты.</StatusLine>
      </section>
    );
  }
  return (
    <section className="card redzone-worker">
      <StatusLine tone={worker.online ? 'ok' : 'warning'}>
        {worker.online ? 'Сервис проверки работает' : 'Сервис проверки не отвечает или занят длинной работой'}
        <span className="hint"> · {ago(worker.seconds_ago)}</span>
      </StatusLine>
      <dl className="redzone-facts">
        <dt>Проверка</dt><dd>{MODES[worker.mode] || worker.mode || '—'}</dd>
        <dt>Облако</dt>
        <dd>{worker.cloud_allowed ? 'разрешено' : 'выключено'}</dd>
        {worker.llm_mode && worker.llm_mode !== 'live' && <><dt>Модель</dt><dd>{LLM_MODES[worker.llm_mode] || worker.llm_mode}</dd></>}
      </dl>
    </section>
  );
}

function ModelOption({ model, active, isDefault, disabled, onChoose }) {
  const tone = model.available ? 'ok' : 'muted';
  return (
    <li>
      <button type="button" role="radio" aria-checked={active} disabled={disabled || (!model.available && !active)}
        className={`card redzone-model ${active ? 'redzone-model-active' : ''} ${model.cloud ? 'redzone-model-cloud' : ''}`}
        onClick={() => !active && onChoose(model)}>
        <Icon name={model.cloud ? 'cloud' : 'chip'} className="redzone-model-icon" />
        <span className="redzone-model-main">
          <span className="redzone-model-title">
            {model.title}
            {isDefault && <span className="redzone-tag">по умолчанию</span>}
          </span>
          {model.model && <span className="redzone-model-name">{model.model}</span>}
          {model.note && <span className="hint">{model.note}</span>}
          <StatusLine tone={tone}>{model.available ? 'Доступна' : (model.reason || 'Недоступна')}</StatusLine>
        </span>
        {active && <Icon name="ok" label="Выбрана" className="redzone-model-check" />}
      </button>
    </li>
  );
}

export default function RedZone({ redZone }) {
  const { system, error, saving, chooseModel } = redZone;
  const [cloudModel, setCloudModel] = useState(null);
  if (!system) {
    return error
      ? <StatusLine tone="error" className="form-status" role="alert">{error}</StatusLine>
      : <p className="empty">Загрузка…</p>;
  }
  const choose = (model) => (model.cloud ? setCloudModel(model) : chooseModel(model.id === system.default_model ? null : model.id));
  const activeModel = system.models.find((m) => m.id === system.active);

  return (
    <>
      <div className="redzone-banner" role="note">
        <Icon name="warning" />
        <span>Изменения сразу действуют на все новые проверки.</span>
      </div>
      {error && <StatusLine tone="error" className="form-status" role="alert">{error}</StatusLine>}

      <WorkerCard worker={system.worker} />

      <section className="admin-section-plain">
        <p className="settings-label">Модель проверки</p>
        {system.models.length === 0 ? (
          <p className="hint" data-tour="redzone-models">Список моделей появится, когда подключится сервис проверки.</p>
        ) : (
          <ul className="redzone-models" role="radiogroup" aria-label="Модель проверки" data-tour="redzone-models">
            {system.models.map((m) => (
              <ModelOption key={m.id} model={m} active={m.id === system.active} isDefault={m.id === system.default_model}
                disabled={saving} onChoose={choose} />
            ))}
          </ul>
        )}
        {activeModel && !activeModel.available && (
          <StatusLine tone="warning">
            Выбранная модель недоступна. Пока работы проверяет модель по умолчанию.
          </StatusLine>
        )}
        {system.changed_at && (
          <p className="hint">
            Изменено {formatDate(system.changed_at, { short: true })}{system.changed_by ? ` · ${system.changed_by}` : ''}
          </p>
        )}
        {system.selected && (
          <button type="button" className="link-btn" disabled={saving} onClick={() => chooseModel(null)}>
            Вернуть модель по умолчанию
          </button>
        )}
      </section>

      <section className="card redzone-privacy">
        <p className="redzone-privacy-title"><Icon name="lock" /><span>Если выбрана облачная модель</span></p>
        <ul>
          <li>Титульный лист не передаётся, а ФИО, почта, телефоны и номера групп в тексте скрываются.</li>
          <li>Студенты увидят при сдаче, что работу проверяет облачный сервис.</li>
        </ul>
      </section>

      {cloudModel && (
        <ConfirmDialog title="Включить облачную модель?" danger confirmLabel="Включить облако"
          body={`Фрагменты работ будут отправляться во внешний сервис (${cloudModel.model || cloudModel.title}). `
            + 'Личные данные перед отправкой скрываются, а студенты увидят это при сдаче.'}
          onConfirm={() => { chooseModel(cloudModel.id, true); setCloudModel(null); }}
          onCancel={() => setCloudModel(null)} />
      )}
    </>
  );
}
