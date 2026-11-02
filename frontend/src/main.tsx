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
  };
  applyScheme();
  WebApp.onEvent('themeChanged', applyScheme);
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)