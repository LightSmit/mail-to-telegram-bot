import { initData } from './telegram'
import { t } from './i18n'

export const REQUEST_TIMEOUT_MS = 20000;
// Добавление включает проверку входа на почтовом сервере: это может занять до минуты
export const LOGIN_TIMEOUT_MS = 70000;

// Относительный путь: в разработке его проксирует Vite, в проде - Caddy.
const API_URL = import.meta.env.VITE_API_URL ?? '/api';

export type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';

export type Request = (path: string, method?: Method, body?: unknown, timeoutMs?: number) => Promise<Response>;

/** Запрос к API бота с данными входа Telegram и таймаутом. */
export const request: Request = async (path, method = 'GET', body, timeoutMs = REQUEST_TIMEOUT_MS) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(`${API_URL}${path}`, {
      method,
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Init-Data': initData,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } finally {
    clearTimeout(timer);
  }
};

export function describeError(error: unknown): string {
  if (error instanceof DOMException && error.name === 'AbortError') return t.serverTimeout;
  return t.noConnection;
}

// 401: Telegram не передал данные входа (приложение открыто не из чата с ботом)
function httpError(prefix: string, status: number): string {
  if (status === 401) return t.reopenFromChat(prefix);
  return `${prefix} (HTTP ${status})`;
}

/** Сервер присылает понятный текст ошибки в поле error. */
export async function errorText(response: Response, prefix: string): Promise<string> {
  try {
    const body = await response.json();
    if (body && typeof body.error === 'string' && response.status !== 401) return body.error;
  } catch {
    // тело не JSON
  }
  return httpError(prefix, response.status);
}

export const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
