import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    // Домены туннелей, через которые Telegram открывает Mini App в разработке.
    // Для своего домена добавь его сюда, например 'app.example.com'.
    allowedHosts: ['.trycloudflare.com'],
    // Браузер/Telegram ходит на тот же origin, а Vite проксирует /api на бэкенд.
    // Так не нужны CORS и http://localhost в коде фронтенда.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
