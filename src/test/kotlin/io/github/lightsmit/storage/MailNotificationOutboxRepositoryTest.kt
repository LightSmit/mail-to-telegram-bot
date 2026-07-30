package io.github.lightsmit.storage

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant

class MailNotificationOutboxRepositoryTest {

    @Test
    fun `enqueue is idempotent for the same email operation`() {
        val repository = createRepository()
        val now = Instant.parse("2026-07-30T12:00:00Z")

        val firstInsert = repository.enqueue(
            accountKey = "imap.mail.ru:user@example.com",
            accountCode = "abcdef123456",
            uidValidity = 100,
            uid = 200,
            now = now,
        )
        val duplicateInsert = repository.enqueue(
            accountKey = "imap.mail.ru:user@example.com",
            accountCode = "abcdef123456",
            uidValidity = 100,
            uid = 200,
            now = now.plusSeconds(5),
        )

        assertTrue(firstInsert)
        assertFalse(duplicateInsert)
        assertEquals(
            1L,
            repository.countByStatus(MailOutboxStatus.PENDING),
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
            now = now,
        )

        val claimed = assertNotNull(repository.claimNextDue(now))
        val retryAt = now.plusSeconds(30)

        repository.markRetry(
            id = claimed.id,
            nextAttemptAt = retryAt,
            error = "Temporary transport failure",
            now = now.plusSeconds(1),
        )

        assertNull(repository.claimNextDue(now.plusSeconds(29)))

        val retried = assertNotNull(repository.claimNextDue(retryAt))
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

    private fun createRepository(): MailNotificationOutboxRepository {
        val directory = createTempDirectory("mail-outbox-test-")
        return MailNotificationOutboxRepository(
            databasePath = directory.resolve("mail-bot.db"),
        )
    }
}
