# Деплой на VPS (Docker Compose + Caddy)

Нужен VPS с Ubuntu 22.04/24.04, **1 ГБ RAM и больше** (JVM на 512 МБ не поместится).

## 1. Подготовка сервера
```bash
curl -fsSL https://get.docker.com | sh
git clone https://github.com/LightSmit/mail-to-telegram-bot.git
cd mail-to-telegram-bot
cp .env.example .env
nano .env
```
В `.env` заполни: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `ENCRYPTION_KEY`
(`openssl rand -base64 48`), `DOMAIN`, хотя бы один `MAIL_ACCOUNT_1_*`.
`TELEGRAM_PROXY_URL` на зарубежном сервере оставь пустым.

## 2. Проверка до запуска
```bash
curl -s -o /dev/null -w "%{http_code}\n" https://api.telegram.org      # 200/302/404 - достучались
openssl s_client -connect imap.mail.ru:993 </dev/null 2>&1 | head -5     # TLS-ответ от Mail.ru
```

## 3. Запуск
```bash
docker compose up -d --build
docker compose logs -f mail-bot
```
Порты 80 и 443 должны быть открыты в фаерволе хостера. Затем в BotFather:
`/mybots` -> бот -> `Bot Settings` -> `Menu Button` -> `https://<DOMAIN>`.

## 4. Обновление и бэкап
```bash
git pull && docker compose up -d --build
docker run --rm -v mail-to-telegram-bot_mail-bot-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/mail-bot-data-$(date +%F).tar.gz /data
```
Ключ `ENCRYPTION_KEY` храни отдельно: без него сохранённые пароли ящиков не расшифровать.
