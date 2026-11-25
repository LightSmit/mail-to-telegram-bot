// Объект Telegram Mini App из официального скрипта (/telegram-web-app.js в index.html).
// Пакет @twa-dev/sdk подключал свою старую копию скрипта и подменял ею новую: в ней нет
// downloadFile и shareMessage. Поэтому берем объект напрямую, из пакета - только типы.
import type SdkWebApp from '@twa-dev/sdk'

type TelegramWebApp = typeof SdkWebApp;

const WebApp = ((window as unknown as { Telegram?: { WebApp?: TelegramWebApp } }).Telegram?.WebApp
  ?? {}) as TelegramWebApp;

export default WebApp;
