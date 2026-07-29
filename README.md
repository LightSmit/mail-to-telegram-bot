# Mail to Telegram Bot

A Kotlin/JVM service that monitors email accounts and forwards new messages to a private Telegram chat.

> **Project status:** early development.

## Planned features

- Multiple email accounts
- Gmail support
- Mail.ru and List.ru support
- Secure IMAP connections
- Forwarding message sender, subject, date, and body
- HTML email processing
- Attachment forwarding
- Protection against duplicate messages
- Configuration through environment variables
- Local state storage with SQLite
- Logging and error handling
- Docker deployment
- Continuous integration with GitHub Actions

## Technology stack

- Kotlin/JVM
- JDK 21
- Gradle Kotlin DSL
- Kotlin Coroutines
- Ktor Client
- Jakarta Mail
- SQLite
- JUnit 5
- Docker

## Requirements

- JDK 21 or newer
- Internet connection

The project includes the Gradle Wrapper, so a separate Gradle installation is not required.

## Build

### Windows

```powershell
.\gradlew.bat clean build
```

### Linux and macOS

```bash
./gradlew clean build
```

## Run

### Windows

```powershell
.\gradlew.bat run
```

### Linux and macOS

```bash
./gradlew run
```

The current application should print:

```text
Mail to Telegram Bot started
```

## Security

Email passwords, Telegram bot tokens, chat identifiers, and other secrets must not be committed to the repository.

Local secrets will be stored in a `.env` file, which is excluded through `.gitignore`. A safe `.env.example` template will be added later.

## License

This project is licensed under the [MIT License](LICENSE).

## Author

[LightSmit](https://github.com/LightSmit)