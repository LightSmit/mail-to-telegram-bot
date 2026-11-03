package io.github.lightsmit.service

import java.time.Duration
import java.time.Instant

/**
 * Жив ли бот: для внешнего мониторинга (адрес /health). Бот считается живым, если недавно
 * успешно получил обновления от Telegram: без этого он не отвечает на кнопки и команды.
 */
object BotHealth {
    @Volatile
    private var lastTelegramPoll: Instant? = null

    private val startedAt: Instant = Instant.now()

    fun telegramPolled(now: Instant = Instant.now()) {
        lastTelegramPoll = now
    }

    fun healthy(now: Instant = Instant.now()): Boolean {
        // Первые минуты после запуска даем на подключение
        val last = lastTelegramPoll ?: return Duration.between(startedAt, now) < STARTUP_GRACE
        return Duration.between(last, now) < MAX_SILENCE
    }

    // Длинный опрос Telegram длится до 25 секунд: две минуты тишины - точно проблема
    private val MAX_SILENCE: Duration = Duration.ofMinutes(2)
    private val STARTUP_GRACE: Duration = Duration.ofMinutes(3)
}
