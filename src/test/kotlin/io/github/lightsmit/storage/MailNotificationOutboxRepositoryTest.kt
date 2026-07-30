package io.github.lightsmit.storage

import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class MailNotificationOutboxRepositoryTest {
    private val testEmailMetadata =
        MailOutboxEmailMetadata(
            from = "Sender <sender@example.com>",
            subject = "Test subject",
            sentAt = Instant.parse(
                "2026-07-30T11:58:00Z",
            ),
            receivedAt = Instant.parse(
                "2026-07-30T11:59:00Z",
            ),
        )

    @Test
    fun `enqueue is idempotent for the same email operation`() {
        val repository = createRepository()
        val now = Instant.parse("2026-07-30T12:00:00Z")

        val firstInsert = repository.enqueue(
            accountKey = "imap.mail.ru:user@example.com",
            accountCode = "abcdef123456",
            uidValidity = 100,
            uid = 200,
            emailMetadata = testEmailMetadata,
            now = now,
        )
        val duplicateInsert = repository.enqueue(
            accountKey = "imap.mail.ru:user@example.com",
            accountCode = "abcdef123456",
            uidValidity = 100,
            uid = 200,
            emailMetadata = testEmailMetadata,
            now = now.plusSeconds(5),
        )

        assertTrue(firstInsert)
        assertFalse(duplicateInsert)
        assertEquals(
            1L,
            repository.countByStatus(
                MailOutboxStatus.PENDING,
            ),
        )

        val stored = assertNotNull(
            repository.find(
                accountKey = "imap.mail.ru:user@example.com",
                uidValidity = 100,
                uid = 200,
            ),
        )

        assertEquals(
            testEmailMetadata,
            stored.emailMetadata,
        )
    }

    @Test
    fun `retry item becomes claimable only after next attempt time`() {
        val repository = createRepository()
        val now = Instant.parse("2026-07-30T12:00:00Z")

        repository.enqueue(
            accountKey = "imap.gmail.com:user@example.com",
            accountCode = "123456abcdef",
            uidValidity = 300,
            uid = 400,
            emailMetadata = testEmailMetadata,
            now = now,
        )

        val claimed = assertNotNull(repository.claimNextDue(now))
        assertEquals(
            testEmailMetadata,
            claimed.emailMetadata,
        )
        val retryAt = now.plusSeconds(30)

        repository.markRetry(
            id = claimed.id,
            nextAttemptAt = retryAt,
            error = "Temporary transport failure",
            now = now.plusSeconds(1),
        )

        assertNull(repository.claimNextDue(now.plusSeconds(29)))

        val retried = assertNotNull(repository.claimNextDue(retryAt))
        assertEquals(
            testEmailMetadata,
            retried.emailMetadata,
        )
        assertEquals(claimed.id, retried.id)
        assertEquals(1, retried.attempts)
        assertEquals(MailOutboxStatus.PROCESSING, retried.status)
    }

    @Test
    fun `processing item is recovered after restart`() {
        val repository = createRepository()
        val now = Instant.parse("2026-07-30T12:00:00Z")

        repository.enqueue(
            accountKey = "imap.mail.ru:user@example.com",
            accountCode = "abcdef123456",
            uidValidity = 500,
            uid = 600,
            emailMetadata = testEmailMetadata,
            now = now,
        )
        val claimed = assertNotNull(repository.claimNextDue(now))

        assertEquals(1, repository.recoverInterrupted(now.plusSeconds(10)))

        val recovered = assertNotNull(
            repository.find(
                accountKey = claimed.accountKey,
                uidValidity = claimed.uidValidity,
                uid = claimed.uid,
            ),
        )

        assertEquals(MailOutboxStatus.RETRY, recovered.status)
        assertNull(recovered.lockedAt)
        assertEquals(
            "Recovered after application restart",
            recovered.lastError,
        )
    }

    @Test
    fun `sent item stores Telegram message id`() {
        val repository = createRepository()
        val now = Instant.parse("2026-07-30T12:00:00Z")

        repository.enqueue(
            accountKey = "imap.list.ru:user@example.com",
            accountCode = "fedcba654321",
            uidValidity = 700,
            uid = 800,
            emailMetadata = testEmailMetadata,
            now = now,
        )
        val claimed = assertNotNull(repository.claimNextDue(now))

        repository.markSent(
            id = claimed.id,
            telegramMessageId = 900,
            now = now.plusSeconds(1),
        )

        val sent = assertNotNull(
            repository.find(
                accountKey = claimed.accountKey,
                uidValidity = claimed.uidValidity,
                uid = claimed.uid,
            ),
        )

        assertEquals(MailOutboxStatus.SENT, sent.status)
        assertEquals(900L, sent.telegramMessageId)
        assertEquals(0, sent.attempts)
    }

    @Test
    fun `sent item cannot be enqueued again`() {
        val repository = createRepository()
        val now = Instant.parse("2026-07-30T12:00:00Z")

        assertTrue(
            repository.enqueue(
                accountKey = "imap.mail.ru:user@example.com",
                accountCode = "abcdef123456",
                uidValidity = 900,
                uid = 1_000,
                emailMetadata = testEmailMetadata,
                now = now,
            ),
        )

        val claimed = assertNotNull(
            repository.claimNextDue(now),
        )

        repository.markSent(
            id = claimed.id,
            telegramMessageId = 1_100,
            now = now.plusSeconds(1),
        )

        val insertedAgain = repository.enqueue(
            accountKey = "imap.mail.ru:user@example.com",
            accountCode = "abcdef123456",
            uidValidity = 900,
            uid = 1_000,
            emailMetadata = testEmailMetadata,
            now = now.plusSeconds(2),
        )

        assertFalse(insertedAgain)

        assertEquals(
            1L,
            repository.countByStatus(
                MailOutboxStatus.SENT,
            ),
        )

        assertEquals(
            0L,
            repository.countByStatus(
                MailOutboxStatus.PENDING,
            ),
        )
    }

    @Test
    fun `completed notification atomically schedules enrichment`() {
        val repository = createRepository()
        val now = Instant.parse(
            "2026-07-30T12:00:00Z",
        )

        repository.enqueue(
            accountKey = "imap.mail.ru:user@example.com",
            accountCode = "abcdef123456",
            uidValidity = 1_100,
            uid = 1_200,
            emailMetadata = testEmailMetadata,
            now = now,
        )

        val notification = assertNotNull(
            repository.claimNextDue(now),
        )

        repository.completeDelivery(
            item = notification,
            telegramMessageId = 1_300,
            now = now.plusSeconds(1),
        )

        val sentNotification = assertNotNull(
            repository.find(
                accountKey = notification.accountKey,
                uidValidity = notification.uidValidity,
                uid = notification.uid,
                operation =
                    MailOutboxOperation.SEND_NOTIFICATION,
            ),
        )

        assertEquals(
            MailOutboxStatus.SENT,
            sentNotification.status,
        )
        assertEquals(
            1_300L,
            sentNotification.telegramMessageId,
        )
        assertTrue(
            sentNotification.summaryActive,
        )

        val enrichment = assertNotNull(
            repository.claimNextDue(
                now.plusSeconds(1),
            ),
        )

        assertEquals(
            MailOutboxOperation.ENRICH_NOTIFICATION,
            enrichment.operation,
        )
        assertEquals(
            MailOutboxStatus.PROCESSING,
            enrichment.status,
        )
        assertEquals(
            testEmailMetadata,
            enrichment.emailMetadata,
        )
        assertFalse(
            enrichment.summaryActive,
        )
    }

    @Test
    fun `summary state survives repository reads`() {
        val repository = createRepository()
        val now = Instant.parse(
            "2026-07-30T12:00:00Z",
        )

        repository.enqueue(
            accountKey = "imap.mail.ru:user@example.com",
            accountCode = "abcdef123456",
            uidValidity = 1_400,
            uid = 1_500,
            emailMetadata = testEmailMetadata,
            now = now,
        )

        val notification = assertNotNull(
            repository.claimNextDue(now),
        )

        repository.completeDelivery(
            item = notification,
            telegramMessageId = 1_600,
            now = now.plusSeconds(1),
        )

        assertTrue(
            repository.markSummaryInactive(
                accountKey = notification.accountKey,
                uidValidity = notification.uidValidity,
                uid = notification.uid,
                now = now.plusSeconds(2),
            ),
        )

        val inactive = assertNotNull(
            repository.find(
                accountKey = notification.accountKey,
                uidValidity = notification.uidValidity,
                uid = notification.uid,
            ),
        )

        assertFalse(
            inactive.summaryActive,
        )

        assertTrue(
            repository.markSummaryReady(
                accountKey = notification.accountKey,
                uidValidity = notification.uidValidity,
                uid = notification.uid,
                telegramMessageId = 1_601,
                now = now.plusSeconds(3),
            ),
        )

        val active = assertNotNull(
            repository.find(
                accountKey = notification.accountKey,
                uidValidity = notification.uidValidity,
                uid = notification.uid,
            ),
        )

        assertTrue(
            active.summaryActive,
        )
        assertEquals(
            1_601L,
            active.telegramMessageId,
        )
    }

    private fun createRepository(): MailNotificationOutboxRepository {
        val directory = createTempDirectory("mail-outbox-test-")
        return MailNotificationOutboxRepository(
            databasePath = directory.resolve("mail-bot.db"),
        )
    }
}