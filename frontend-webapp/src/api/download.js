import { API_BASE, apiFetch } from './client';
import { getInitData, isTelegramClient } from '../telegram/webApp';

// Открываем URL в браузере — позволяет просмотреть/скачать файл на всех платформах
const openDownloadUrl = (apiPath) => {
  const url = `${API_BASE}${apiPath}?tg_auth=${encodeURIComponent(getInitData())}`;
  window.open(url, '_blank');
};

const downloadBlob = (blob, filename) => {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click();
  document.body.removeChild(a); URL.revokeObjectURL(url);
};

export const downloadFile = async (apiPath, filename) => {
  if (isTelegramClient()) {
    openDownloadUrl(apiPath);
    return;
  }
  try {
    const res = await apiFetch(apiPath);
    if (res.ok) downloadBlob(await res.blob(), filename);
    else openDownloadUrl(apiPath);
  } catch { openDownloadUrl(apiPath); }
};
