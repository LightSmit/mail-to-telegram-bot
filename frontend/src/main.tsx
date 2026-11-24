import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import WebApp from '@twa-dev/sdk'
import App from './App.tsx'
import { telegramScriptLoaded } from './telegram'
import { lang } from './i18n'
import './index.css'

document.documentElement.lang = lang;

// Вызываем методы Telegram, только если официальный скрипт загрузился
if (telegramScriptLoaded) {
  WebApp.ready();
  WebApp.expand();

  // Светлая или темная тема Telegram: от нее зависит оттенок фирменного цвета
  const applyScheme = () => {
    document.documentElement.dataset.tgScheme = WebApp.colorScheme;
    // В темной теме у приложения своя палитра: красим шапку и низ Telegram в те же цвета
    if (WebApp.colorScheme !== 'light') {
      const colors = WebApp as unknown as Record<string, ((color: string) => void) | undefined>;
      try {
        colors.setHeaderColor?.call(WebApp, '#0b1320');
        colors.setBackgroundColor?.call(WebApp, '#0b1320');
        colors.setBottomBarColor?.call(WebApp, '#152030');
      } catch {
        // старый клиент Telegram: оставляем его цвета
      }
    }
  };
  applyScheme();
  WebApp.onEvent('themeChanged', applyScheme);
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)