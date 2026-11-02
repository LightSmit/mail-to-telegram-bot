package io.github.lightsmit.service

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.telegram.TelegramClient
import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class MailboxStatus { CONNECTING, OK, ERROR }

data class MailboxHealth(
    val status: MailboxStatus,
    val problem: String? = null,
)

/**
 * Следит, работает ли подключение к каждому ящику. Если ящик перестал
 * отвечать (сменили пароль, удалили пароль приложения, сервер недоступен),
 * один раз пишет владельцу и еще раз - когда ящик снова заработает.
 */
class MailboxHealthMonitor(
    private val telegramClient: TelegramClient,
    private val miniAppUrl: String?,
    private val clock: () -> Instant = Instant::now,
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(MailboxHealthMonitor::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class State {
        var status = MailboxStatus.CONNECTING
        var problem: String? = null
        var consecutiveFailures = 0
        var consecutiveAuthFailures = 0
        var firstFailureAt: Instant? = null
        var ownerNotified = false
    }

    private val states = ConcurrentHashMap<Long, State>()

    fun health(accountId: Long): MailboxHealth {
        val state = states[accountId] ?: return MailboxHealth(MailboxStatus.CONNECTING)
        return synchronized(state) { MailboxHealth(state.status, state.problem) }
    }

    /** Подключение (или проверка ящика) прошло успешно. */
    fun onConnected(account: MailAccountConfig) {
        val state = states.computeIfAbsent(account.id) { State() }
        val shouldNotifyRecovery = synchronized(state) {
            val wasNotified = state.ownerNotified
            state.status = MailboxStatus.OK
            state.problem = null
            state.consecutiveFailures = 0
            state.consecutiveAuthFailures = 0
            state.firstFailureAt = null
            state.ownerNotified = false
            wasNotified
        }

        if (shouldNotifyRecovery) {
            send(account, "✅ Ящик «${account.name}» (${account.username}) снова работает. Новые письма будут приходить, как обычно.")
        }
    }

    /** Ошибка подключения или проверки ящика. */
    fun onFailure(account: MailAccountConfig, exception: Exception) {
        val isAuthFailure = exception.isAuthenticationFailure()
        val reason = if (isAuthFailure) {
            "почта отклонила пароль приложения (его могли удалить, или сменился пароль от почты)"
        } else {
            "не удается подключиться к серверу ${account.host}"
        }

        val state = states.computeIfAbsent(account.id) { State() }
        val shouldNotify = synchronized(state) {
            val now = clock()
            state.consecutiveFailures++
            state.consecutiveAuthFailures = if (isAuthFailure) state.consecutiveAuthFailures + 1 else 0
            if (state.firstFailureAt == null) state.firstFailureAt = now

            val failingFor = Duration.between(state.firstFailureAt, now)
            val broken = state.consecutiveAuthFailures >= AUTH_FAILURES_BEFORE_ALERT ||
                failingFor >= NETWORK_FAILURE_ALERT_AFTER

            // На карточке в приложении показываем проблему сразу после пары неудач подряд
            if (broken || state.consecutiveFailures >= 2) {
                state.status = MailboxStatus.ERROR
                state.problem = reason.replaceFirstChar(Char::uppercase)
            }

            if (broken && !state.ownerNotified) {
                state.ownerNotified = true
                true
            } else {
                false
            }
        }

        if (shouldNotify) {
            logger.warn("Mailbox {} (id {}) is failing; notifying owner {}", account.username, account.id, account.ownerChatId)
            send(
                account,
                buildString {
                    appendLine("⚠️ Не удается проверить ящик «${account.name}» (${account.username})")
                    appendLine()
                    appendLine("Причина: $reason.")
                    appendLine()
                    appendLine("Если вы меняли пароль от почты или удаляли пароль приложения, создайте новый пароль приложения и введите его в приложении: «Настройки» у этого ящика, раздел «Пароль приложения». Фильтры при этом сохранятся.")
                    append("Бот продолжит попытки и напишет, когда ящик снова заработает.")
                },
            )
        }
    }

    /** Ящик удален или добавлен заново: начинаем наблюдение с чистого листа. */
    fun forget(accountId: Long) {
        states.remove(accountId)
    }

    private fun send(account: MailAccountConfig, text: String) {
        scope.launch {
            runCatching {
                if (miniAppUrl != null) {
                    telegramClient.sendMessageWithWebAppButton(
                        chatId = account.ownerChatId,
                        text = text,
                        buttonText = "Открыть приложение",
                        webAppUrl = miniAppUrl,
                    )
                } else {
                    telegramClient.sendMessage(chatId = account.ownerChatId, text = text)
                }
            }.onFailure { failure ->
                logger.warn("Failed to send mailbox health message to {}: {}", account.ownerChatId, failure.javaClass.simpleName)
            }
        }
    }

    private fun Throwable.isAuthenticationFailure(): Boolean {
        var current: Throwable? = this
        repeat(5) {
            if (current is AuthenticationFailedException) return true
            current = current?.cause ?: return false
        }
        return false
    }

    override fun close() {
        scope.cancel()
    }

    private companion object {
        // Ошибки входа повторяются с растущей паузой: 3 подряд - это уже не случайность
        const val AUTH_FAILURES_BEFORE_ALERT = 3
        // Сетевые сбои бывают у самих почтовиков; пишем, только если ящик молчит долго
        val NETWORK_FAILURE_ALERT_AFTER: Duration = Duration.ofMinutes(10)
    }
}
