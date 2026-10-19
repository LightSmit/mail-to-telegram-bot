import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import WebApp from '@twa-dev/sdk'
import App from './App.tsx'
import './index.css'

// Вызываем методы Telegram только если мы реально внутри Telegram
if (WebApp.initData) {
  WebApp.ready();
  WebApp.expand();
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)