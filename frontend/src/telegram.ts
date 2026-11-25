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

export type InvoiceStatus = 'paid' | 'cancelled' | 'failed' | 'pending' | 'unsupported';

type InvoiceApi = (url: string, callback?: (status: string) => void) => void;

/**
 * Открывает окно оплаты звездами по ссылке из createInvoiceLink.
 * Без скрипта Telegram (или в старом клиенте) оплату открыть нельзя: 'unsupported'.
 */
export function openInvoice(link: string): Promise<InvoiceStatus> {
  const open = (WebApp as unknown as { openInvoice?: InvoiceApi }).openInvoice;
  if (!telegramScriptLoaded || typeof open !== 'function') return Promise.resolve('unsupported');
  return new Promise((resolve) => {
    try {
      open.call(WebApp, link, (status) => {
        resolve(status === 'paid' || status === 'cancelled' || status === 'pending' ? status : 'failed');
      });
    } catch {
      resolve('unsupported');
    }
  });
}

/** Открывает ссылку t.me внутри Telegram (например, выбор группы для бота). */
export function openTelegramLink(link: string): void {
  const open = (WebApp as unknown as { openTelegramLink?: (url: string) => void }).openTelegramLink;
  if (telegramScriptLoaded && typeof open === 'function') {
    try {
      open.call(WebApp, link);
      return;
    } catch {
      // ниже откроем обычной ссылкой
    }
  }
  window.open(link, '_blank');
}

/**
 * Открывает обычную ссылку (настройки почты) в браузере. Внутри Telegram -
 * через openLink, иначе мини-приложение закрылось бы при переходе.
 */
export function openLink(link: string): void {
  const open = (WebApp as unknown as { openLink?: (url: string) => void }).openLink;
  if (telegramScriptLoaded && typeof open === 'function') {
    try {
      open.call(WebApp, link);
      return;
    } catch {
      // ниже откроем обычной ссылкой
    }
  }
  window.open(link, '_blank', 'noopener');
}

/**
 * Скачать файл штатным окном Telegram (Bot API 8.0+). false - клиент не умеет,
 * нужен запасной путь.
 */
export function downloadFile(url: string, fileName: string): boolean {
  const app = WebApp as unknown as {
    downloadFile?: (params: { url: string; file_name: string }, callback?: (accepted: boolean) => void) => void;
    isVersionAtLeast?: (version: string) => boolean;
  };
  if (!telegramScriptLoaded || typeof app.downloadFile !== 'function') return false;
  if (typeof app.isVersionAtLeast === 'function' && !app.isVersionAtLeast('8.0')) return false;
  try {
    app.downloadFile.call(WebApp, { url, file_name: fileName });
    return true;
  } catch {
    return false;
  }
}

/** Параметр запуска: из ссылки t.me/бот?startapp=... или из кнопки бота (?open=...). */
export function launchParam(): string | null {
  const fromLink = (WebApp as unknown as { initDataUnsafe?: { start_param?: string } }).initDataUnsafe?.start_param;
  if (fromLink) return fromLink;
  try {
    return new URLSearchParams(window.location.search).get('open');
  } catch {
    return null;
  }
}
