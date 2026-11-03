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
