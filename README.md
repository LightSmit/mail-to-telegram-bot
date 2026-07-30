# Mail to Telegram Bot

A Kotlin/JVM service that monitors multiple IMAP mailboxes and sends new-email notifications to a private Telegram chat.

> **Project status:** `v0.1.0-alpha.1` — working alpha release intended for local testing.

## Features

- Multiple IMAP mailboxes
- Gmail, Mail.ru, and List.ru support
- Secure IMAP connections
- Real-time monitoring through IMAP IDLE
- Safety polling as a fallback
- SQLite state storage
- Duplicate-notification protection
- Plain-text and HTML email processing
- Background prefetching of email content
- Interactive Telegram interface:
  - compact email notification;
  - full text view;
  - attachment list;
  - back navigation;
  - separate attachment download
- Attachments are sent as Telegram documents with their original names
- Configurable attachment-size limit
- Optional HTTP proxy used only for Telegram Bot API requests
- Local JVM and Docker launch modes
- Token-safe transport errors in application logs

## Technology stack

- Kotlin/JVM
- JDK 21
- Gradle Kotlin DSL
- Kotlin Coroutines
- Ktor Client with CIO
- Jakarta Mail
- SQLite
- jsoup
- JUnit 5
- Docker and Docker Compose

## Requirements

### Local launch

- JDK 21 or newer
- Internet access to the configured IMAP servers
- Access to Telegram Bot API, directly or through an HTTP proxy

The project includes the Gradle Wrapper, so a separate Gradle installation is not required.

### Docker launch

- Docker Desktop or Docker Engine
- Docker Compose

## Configuration

Copy the example configuration:

### Windows

```powershell
Copy-Item .env.example .env
```

### Linux and macOS

```bash
cp .env.example .env
```

Fill in `.env`:

```dotenv
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
TELEGRAM_PROXY_URL=

MAIL_ACCOUNT_1_NAME=Primary Mail
MAIL_ACCOUNT_1_HOST=imap.mail.ru
MAIL_ACCOUNT_1_PORT=993
MAIL_ACCOUNT_1_USERNAME=
MAIL_ACCOUNT_1_PASSWORD=

DATABASE_PATH=data/mail-bot.db
ATTACHMENT_TEMP_DIR=data/attachments
MAX_ATTACHMENT_SIZE_MB=45

IDLE_RECONNECT_DELAY_SECONDS=5
IMAP_SAFETY_POLL_SECONDS=10
```

To configure more mailboxes, repeat the numbered mail-account block:

```dotenv
MAIL_ACCOUNT_2_NAME=Gmail
MAIL_ACCOUNT_2_HOST=imap.gmail.com
MAIL_ACCOUNT_2_PORT=993
MAIL_ACCOUNT_2_USERNAME=
MAIL_ACCOUNT_2_PASSWORD=
```

Use app-specific email passwords where the provider supports them.

## Telegram proxy

`TELEGRAM_PROXY_URL` affects only Telegram Bot API requests. IMAP connections remain direct.

Local Windows launch through a local HTTP proxy:

```dotenv
TELEGRAM_PROXY_URL=http://127.0.0.1:10809
```

Docker Desktop launch through a proxy running on the host:

```dotenv
TELEGRAM_PROXY_URL=http://host.docker.internal:10809
```

Direct Telegram access:

```dotenv
TELEGRAM_PROXY_URL=
```

MTProto proxies configured inside the Telegram application cannot be used as an HTTP proxy for Telegram Bot API requests.

## Build

### Windows

```powershell
.\gradlew.bat clean build
```

### Linux and macOS

```bash
./gradlew clean build
```

## Run locally

### Windows

```powershell
.\gradlew.bat run
```

### Linux and macOS

```bash
./gradlew run
```

Runtime data is stored in:

```text
data/mail-bot.db
data/attachments/
```

Stop the application with `Ctrl+C`.

## Run with Docker

Build and start:

```bash
docker compose up -d --build
```

View logs:

```bash
docker compose logs -f mail-bot
```

Stop:

```bash
docker compose stop
```

Do not run `docker compose down -v` unless you intentionally want to delete the named volume containing SQLite state.

## Telegram interaction

A new email produces a compact notification containing mailbox, sender, recipient, subject, date, and attachment names.

Available actions:

- **Text** — replaces the compact notification with the full email text
- **Attachments** — sends attachments as separate documents while keeping the current email message
- **Back** — closes the full-text view and restores the compact notification

Only the configured private Telegram chat is allowed to control the bot.

## Security

Never commit:

- `.env`
- Telegram bot tokens
- Telegram chat identifiers
- email passwords
- SQLite databases
- downloaded attachments
- diagnostic logs containing request URLs

The repository ignores local secrets and runtime data through `.gitignore`.

If a Telegram token has appeared in logs or chat messages, revoke it through BotFather and issue a new token.

## Current limitations

This is an alpha release. The next reliability stage will add:

- persistent SQLite outbox;
- retry scheduling with backoff;
- recovery of pending Telegram operations after restart;
- stricter error classification;
- automated tests and CI.

## License

This project is licensed under the [MIT License](LICENSE).

## Author

[LightSmit](https://github.com/LightSmit)
