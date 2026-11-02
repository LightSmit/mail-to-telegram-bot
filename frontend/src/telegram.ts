import WebApp from '@twa-dev/sdk'

// Официальный скрипт Telegram (telegram-web-app.js) может не загрузиться,
// например, если telegram.org недоступен у пользователя. Тогда объект WebApp
// пустой, хотя Telegram все равно передает подписанные данные в адресе
// страницы: #tgWebAppData=...  Читаем их сами как запасной вариант.

type TelegramGlobal = { Telegram?: { WebApp?: { initData?: string } } };

function readFromHash(): string {
  try {
    const params = new URLSearchParams(window.location.hash.slice(1));
    return params.get('tgWebAppData') ?? '';
  } catch {
    return '';
  }
}

function resolveInitData(): string {
  const fromSdk = WebApp.initData || (window as unknown as TelegramGlobal).Telegram?.WebApp?.initData;
  if (fromSdk) return fromSdk;

  const fromHash = readFromHash();
  if (fromHash) {
    try { sessionStorage.setItem('tgWebAppData', fromHash); } catch { /* хранилище недоступно */ }
    return fromHash;
  }

  // После перезагрузки страницы hash может исчезнуть
  try { return sessionStorage.getItem('tgWebAppData') ?? ''; } catch { return ''; }
}

/** Подписанные данные Telegram, вычисляются один раз при загрузке страницы. */
export const initData: string = resolveInitData();

/** Загрузился ли официальный скрипт Telegram (нужен для ready/expand/тем). */
const platform = String((WebApp as { platform?: unknown }).platform ?? '');
export const telegramScriptLoaded: boolean = platform !== '' && platform !== 'unknown';

/** Откуда взялись данные: для диагностики. */
export const initDataSource: string =
  WebApp.initData ? 'sdk' : initData ? 'адрес страницы' : 'нет';

type BackButtonApi = {
  show: () => void;
  hide: () => void;
  onClick: (handler: () => void) => void;
  offClick: (handler: () => void) => void;
};

/**
 * Системная кнопка «Назад» Telegram в шапке приложения. Возвращает функцию,
 * которая убирает кнопку. Без скрипта Telegram ничего не делает.
 */
export function showBackButton(handler: () => void): () => void {
  const button = (WebApp as unknown as { BackButton?: BackButtonApi }).BackButton;
  if (!telegramScriptLoaded || !button) return () => {};
  try {
    button.onClick(handler);
    button.show();
  } catch {
    return () => {};
  }
  return () => {
    try {
      button.offClick(handler);
      button.hide();
    } catch {
      // кнопка уже недоступна
    }
  };
}
