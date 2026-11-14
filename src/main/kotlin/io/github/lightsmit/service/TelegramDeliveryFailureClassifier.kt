package io.github.lightsmit.service

import io.github.lightsmit.telegram.TelegramApiException
import io.github.lightsmit.telegram.TelegramTransportException
import java.time.Duration

sealed interface DeliveryFailureDecision {

    val reason: String

    data class Retry(
        override val reason: String,
        val delay: Duration,
    ) : DeliveryFailureDecision

    data class Dead(
        override val reason: String,
    ) : DeliveryFailureDecision
}

class PermanentMailDeliveryException(
    message: String,
) : RuntimeException(message)

class TelegramDeliveryFailureClassifier(
    private val baseRetryDelay: Duration = Duration.ofSeconds(5),
    private val maximumRetryDelay: Duration = Duration.ofMinutes(15),
) {

    init {
        require(!baseRetryDelay.isZero && !baseRetryDelay.isNegative) {
            "Base retry delay must be positive"
        }
        require(!maximumRetryDelay.isZero && !maximumRetryDelay.isNegative) {
            "Maximum retry delay must be positive"
        }
        require(maximumRetryDelay >= baseRetryDelay) {
            "Maximum retry delay must not be shorter than base retry delay"
        }
    }

    /**
     * previousError - причина прошлой неудачи этой же задачи. Если и прошлая, и текущая
     * отправка закончились без ответа Telegram (сообщение могло дойти), больше не повторяем:
     * лучше одно уведомление, чем десятки дублей при плохой связи.
     */
    fun classify(
        exception: Throwable,
        failedAttempts: Int,
        previousError: String? = null,
    ): DeliveryFailureDecision {
        require(failedAttempts > 0) {
            "Failed attempts count must be greater than zero"
        }

        if (exception is TelegramTransportException && exception.maybeDelivered &&
            previousError?.contains(UNCONFIRMED_MARKER) == true
        ) {
            return DeliveryFailureDecision.Dead(
                reason = "Telegram did not confirm delivery twice, not resending to avoid duplicates",
            )
        }

        val decision = classifyException(exception, failedAttempts)
        // Не повторяем бесконечно: после MAX_ATTEMPTS неудач задача закрывается
        if (decision is DeliveryFailureDecision.Retry && failedAttempts >= MAX_ATTEMPTS) {
            return DeliveryFailureDecision.Dead(reason = "Gave up after $failedAttempts attempts: ${decision.reason}")
        }
        return decision
    }

    private fun classifyException(
        exception: Throwable,
        failedAttempts: Int,
    ): DeliveryFailureDecision {
        return when (exception) {
            is PermanentMailDeliveryException -> {
                DeliveryFailureDecision.Dead(
                    reason = exception.message
                        ?: "Permanent mail delivery failure",
                )
            }

            is TelegramTransportException -> {
                DeliveryFailureDecision.Retry(
                    reason = exception.message
                        ?: "Telegram transport failure",
                    delay = calculateBackoff(failedAttempts),
                )
            }

            is TelegramApiException -> {
                classifyApiFailure(
                    exception = exception,
                    failedAttempts = failedAttempts,
                )
            }

            else -> {
                DeliveryFailureDecision.Retry(
                    reason = buildString {
                        append("Unexpected delivery failure: ")
                        append(exception.javaClass.simpleName)
                    },
                    delay = calculateBackoff(failedAttempts),
                )
            }
        }
    }

    private fun classifyApiFailure(
        exception: TelegramApiException,
        failedAttempts: Int,
    ): DeliveryFailureDecision {
        val errorCode = exception.errorCode

        if (
            errorCode == 429 ||
            exception.retryAfterSeconds != null
        ) {
            val delay = exception.retryAfterSeconds
                ?.coerceIn(
                    1,
                    maximumRetryDelay.seconds.toInt(),
                )
                ?.let { seconds ->
                    Duration.ofSeconds(seconds.toLong())
                }
                ?: calculateBackoff(failedAttempts)

            return DeliveryFailureDecision.Retry(
                reason = exception.message
                    ?: "Telegram rate limit exceeded",
                delay = delay,
            )
        }

        if (errorCode != null && errorCode in 500..599) {
            return DeliveryFailureDecision.Retry(
                reason = exception.message
                    ?: "Telegram server error",
                delay = calculateBackoff(failedAttempts),
            )
        }

        if (errorCode != null && errorCode in 400..499) {
            return DeliveryFailureDecision.Dead(
                reason = exception.message
                    ?: "Permanent Telegram API error",
            )
        }

        val normalizedDescription = exception.description.lowercase()

        if (
            "unauthorized" in normalizedDescription ||
            "bot was blocked" in normalizedDescription ||
            "chat not found" in normalizedDescription ||
            "user is deactivated" in normalizedDescription
        ) {
            return DeliveryFailureDecision.Dead(
                reason = exception.message
                    ?: "Permanent Telegram API error",
            )
        }

        return DeliveryFailureDecision.Retry(
            reason = exception.message
                ?: "Unclassified Telegram API error",
            delay = calculateBackoff(failedAttempts),
        )
    }

    private fun calculateBackoff(
        failedAttempts: Int,
    ): Duration {
        val exponent = (failedAttempts - 1)
            .coerceIn(0, MAX_BACKOFF_EXPONENT)

        val multiplier = 1L shl exponent

        val delaySeconds = (
                baseRetryDelay.seconds * multiplier
                ).coerceAtMost(maximumRetryDelay.seconds)

        return Duration.ofSeconds(delaySeconds)
    }

    companion object {
        private const val MAX_BACKOFF_EXPONENT = 8
        // Около 4 часов повторов с паузой до 15 минут
        const val MAX_ATTEMPTS = 20
        // Так помечается ошибка, после которой сообщение могло быть доставлено
        const val UNCONFIRMED_MARKER = "delivery unknown"
    }
}