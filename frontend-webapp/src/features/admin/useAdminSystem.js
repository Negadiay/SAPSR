import { useEffect, useState } from 'react';
import { fetchSystem, saveModel } from '../../api/admin';
import { readError } from '../../api/client';

// Обработчик сообщает о себе раз в минуту — экран обновляется чаще, чтобы «на связи» не отставало.
const REFRESH_MS = 15000;

// Состояние «Красной зоны»: обработчик, его модели и выбранная модель проверки.
export function useAdminSystem({ active }) {
  const [system, setSystem] = useState(null);
  const [error, setError]   = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!active) return undefined;
    let cancelled = false;
    const load = async () => {
      try {
        const res = await fetchSystem();
        if (cancelled) return;
        if (res.ok) {
          setSystem(await res.json());
          setError('');
        } else {
          setError((await readError(res)).error || 'Не удалось загрузить.');
        }
      } catch {
        if (!cancelled) setError('Нет связи с сервером.');
      }
    };
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => { cancelled = true; clearInterval(timer); };
  }, [active]);

  const chooseModel = async (model, confirmCloud = false) => {
    setSaving(true);
    setError('');
    try {
      const res = await saveModel(model, confirmCloud);
      if (!res.ok) {
        setError((await readError(res)).error || 'Не удалось сохранить.');
        return false;
      }
      setSystem(await res.json());
      return true;
    } catch {
      setError('Нет связи с сервером.');
      return false;
    } finally { setSaving(false); }
  };

  return { system, error, saving, chooseModel };
}
