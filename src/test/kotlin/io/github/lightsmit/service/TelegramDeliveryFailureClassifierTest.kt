package io.github.lightsmit.service

import io.github.lightsmit.telegram.TelegramApiException
import io.github.lightsmit.telegram.TelegramTransportException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelegramDeliveryFailureClassifierTest {

    private val classifier =
        TelegramDeliveryFailureClassifier()

    @Test
    fun `transport failure is retryable`() {
        val decision = classifier.classify(
            exception = TelegramTransportException(
                operation = "sendMessage",
                causeType = "ConnectTimeoutException",
            ),
            failedAttempts = 1,
        )

        val retry =
            assertIs<DeliveryFailureDecision.Retry>(decision)

        assertEquals(
            Duration.ofSeconds(5),
            retry.delay,
        )
    }

    @Test
    fun `rate limit uses Telegram retry after value`() {
        val decision = classifier.classify(
            exception = TelegramApiException(
                description = "Too Many Requests",
                retryAfterSeconds = 37,
                errorCode = 429,
            ),
            failedAttempts = 1,
        )

        val retry =
            assertIs<DeliveryFailureDecision.Retry>(decision)

        assertEquals(
            Duration.ofSeconds(37),
            retry.delay,
        )
    }

    @Test
    fun `unauthorized error is permanent`() {
        val decision = classifier.classify(
            exception = TelegramApiException(
                description = "Unauthorized",
                errorCode = 401,
            ),
            failedAttempts = 1,
        )

        assertIs<DeliveryFailureDecision.Dead>(decision)
    }

    @Test
    fun `server failure uses exponential backoff`() {
        val decision = classifier.classify(
            exception = TelegramApiException(
                description = "Internal Server Error",
                errorCode = 500,
            ),
            failedAttempts = 3,
        )

        val retry =
            assertIs<DeliveryFailureDecision.Retry>(decision)

        assertEquals(
            Duration.ofSeconds(20),
            retry.delay,
        )
    }

    @Test
    fun `permanent mail delivery failure is dead`() {
        val decision = classifier.classify(
            exception = PermanentMailDeliveryException(
                "Configured mail account no longer exists",
            ),
            failedAttempts = 1,
        )

        val dead =
            assertIs<DeliveryFailureDecision.Dead>(decision)

        assertEquals(
            "Configured mail account no longer exists",
            dead.reason,
        )
    }

    @Test
    fun `unconfirmed send is never repeated`() {
        val unconfirmed = TelegramTransportException(
            operation = "sendMessage",
            causeType = "HttpRequestTimeoutException",
            maybeDelivered = true,
        )

        // Даже на первой попытке: сообщение, скорее всего, дошло, повтор дал бы дубль
        assertIs<DeliveryFailureDecision.Dead>(classifier.classify(exception = unconfirmed, failedAttempts = 1))
        assertTrue(classifier.isUnconfirmed(unconfirmed))

        // А если не удалось даже подключиться, повторять безопасно
        val notSent = TelegramTransportException(operation = "sendMessage", causeType = "ConnectException")
        assertIs<DeliveryFailureDecision.Retry>(classifier.classify(exception = notSent, failedAttempts = 5))
        assertFalse(classifier.isUnconfirmed(notSent))
    }

    @Test
    fun `retries stop after the attempt limit`() {
        val decision = classifier.classify(
            exception = TelegramTransportException(operation = "sendMessage", causeType = "ConnectException"),
            failedAttempts = TelegramDeliveryFailureClassifier.MAX_ATTEMPTS,
        )
        assertIs<DeliveryFailureDecision.Dead>(decision)
    }
}
