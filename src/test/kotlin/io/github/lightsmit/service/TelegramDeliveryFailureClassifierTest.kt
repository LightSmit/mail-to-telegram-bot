package io.github.lightsmit.service

import io.github.lightsmit.telegram.TelegramApiException
import io.github.lightsmit.telegram.TelegramTransportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import java.time.Duration

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
}