package io.github.lightsmit.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

enum class MailOutboxOperation {
    SEND_NOTIFICATION,
}

enum class MailOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY,
    SENT,
    DEAD,
}

data class MailOutboxItem(
    val id: Long,
    val accountKey: String,
    val accountCode: String,
    val uidValidity: Long,
    val uid: Long,
    val operation: MailOutboxOperation,
    val status: MailOutboxStatus,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val lockedAt: Instant?,
    val telegramMessageId: Long?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

class MailNotificationOutboxRepository(
    databasePath: Path,
) {

    private val databaseUrl: String

    init {
        val absolutePath = databasePath
            .toAbsolutePath()
            .normalize()

        absolutePath.parent?.let(Files::createDirectories)
        databaseUrl = "jdbc:sqlite:$absolutePath"

        openConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA synchronous = NORMAL")

                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS mail_notification_outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        account_key TEXT NOT NULL,
                        account_code TEXT NOT NULL,
                        uid_validity INTEGER NOT NULL CHECK (uid_validity > 0),
                        uid INTEGER NOT NULL CHECK (uid > 0),
                        operation TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
                        next_attempt_at INTEGER NOT NULL,
                        locked_at INTEGER,
                        telegram_message_id INTEGER,
                        last_error TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE (account_key, uid_validity, uid, operation)
                    )
                    """.trimIndent(),
                )

                statement.executeUpdate(
                    """
                    CREATE INDEX IF NOT EXISTS idx_mail_notification_outbox_due
                    ON mail_notification_outbox (
                        status,
                        next_attempt_at,
                        id
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    @Synchronized
    fun enqueue(
        accountKey: String,
        accountCode: String,
        uidValidity: Long,
        uid: Long,
        operation: MailOutboxOperation = MailOutboxOperation.SEND_NOTIFICATION,
        now: Instant = Instant.now(),
    ): Boolean {
        require(accountKey.isNotBlank()) {
            "Outbox account key must not be blank"
        }
        require(accountCode.isNotBlank()) {
            "Outbox account code must not be blank"
        }
        require(uidValidity > 0) {
            "UIDVALIDITY must be greater than zero"
        }
        require(uid > 0) {
            "UID must be greater than zero"
        }

        val timestamp = now.toEpochMilli()
        val sql =
            """
            INSERT OR IGNORE INTO mail_notification_outbox (
                account_key,
                account_code,
                uid_validity,
                uid,
                operation,
                status,
                attempts,
                next_attempt_at,
                locked_at,
                telegram_message_id,
                last_error,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, 0, ?, NULL, NULL, NULL, ?, ?)
            """.trimIndent()

        openConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, accountKey)
                statement.setString(2, accountCode)
                statement.setLong(3, uidValidity)
                statement.setLong(4, uid)
                statement.setString(5, operation.name)
                statement.setString(6, MailOutboxStatus.PENDING.name)
                statement.setLong(7, timestamp)
                statement.setLong(8, timestamp)
                statement.setLong(9, timestamp)
                return statement.executeUpdate() == 1
            }
        }
    }

    @Synchronized
    fun claimNextDue(
        now: Instant = Instant.now(),
    ): MailOutboxItem? {
        val timestamp = now.toEpochMilli()
        val sql =
            """
            UPDATE mail_notification_outbox
            SET
                status = ?,
                locked_at = ?,
                updated_at = ?
            WHERE id = (
                SELECT id
                FROM mail_notification_outbox
                WHERE status IN (?, ?)
                  AND next_attempt_at <= ?
                ORDER BY next_attempt_at ASC, id ASC
                LIMIT 1
            )
            RETURNING
                id,
                account_key,
                account_code,
                uid_validity,
                uid,
                operation,
                status,
                attempts,
                next_attempt_at,
                locked_at,
                telegram_message_id,
                last_error,
                created_at,
                updated_at
            """.trimIndent()

        openConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, MailOutboxStatus.PROCESSING.name)
                statement.setLong(2, timestamp)
                statement.setLong(3, timestamp)
                statement.setString(4, MailOutboxStatus.PENDING.name)
                statement.setString(5, MailOutboxStatus.RETRY.name)
                statement.setLong(6, timestamp)

                statement.executeQuery().use { result ->
                    return if (result.next()) {
                        result.toOutboxItem()
                    } else {
                        null
                    }
                }
            }
        }
    }

    @Synchronized
    fun markSent(
        id: Long,
        telegramMessageId: Long,
        now: Instant = Instant.now(),
    ) {
        require(id > 0) {
            "Outbox ID must be greater than zero"
        }
        require(telegramMessageId > 0) {
            "Telegram message ID must be greater than zero"
        }

        updateTerminalState(
            id = id,
            status = MailOutboxStatus.SENT,
            telegramMessageId = telegramMessageId,
            lastError = null,
            incrementAttempts = false,
            now = now,
        )
    }

    @Synchronized
    fun markRetry(
        id: Long,
        nextAttemptAt: Instant,
        error: String,
        now: Instant = Instant.now(),
    ) {
        require(id > 0) {
            "Outbox ID must be greater than zero"
        }
        require(!nextAttemptAt.isBefore(now)) {
            "Next retry time must not be before the current time"
        }

        val sql =
            """
            UPDATE mail_notification_outbox
            SET
                status = ?,
                attempts = attempts + 1,
                next_attempt_at = ?,
                locked_at = NULL,
                last_error = ?,
                updated_at = ?
            WHERE id = ?
              AND status = ?
            """.trimIndent()

        openConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, MailOutboxStatus.RETRY.name)
                statement.setLong(2, nextAttemptAt.toEpochMilli())
                statement.setString(3, sanitizeError(error))
                statement.setLong(4, now.toEpochMilli())
                statement.setLong(5, id)
                statement.setString(6, MailOutboxStatus.PROCESSING.name)

                check(statement.executeUpdate() == 1) {
                    "Outbox item $id is not in PROCESSING state"
                }
            }
        }
    }

    @Synchronized
    fun markDead(
        id: Long,
        error: String,
        now: Instant = Instant.now(),
    ) {
        require(id > 0) {
            "Outbox ID must be greater than zero"
        }

        updateTerminalState(
            id = id,
            status = MailOutboxStatus.DEAD,
            telegramMessageId = null,
            lastError = sanitizeError(error),
            incrementAttempts = true,
            now = now,
        )
    }

    @Synchronized
    fun recoverInterrupted(
        now: Instant = Instant.now(),
    ): Int {
        val sql =
            """
            UPDATE mail_notification_outbox
            SET
                status = ?,
                next_attempt_at = ?,
                locked_at = NULL,
                last_error = COALESCE(
                    last_error,
                    'Recovered after application restart'
                ),
                updated_at = ?
            WHERE status = ?
            """.trimIndent()

        openConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                val timestamp = now.toEpochMilli()
                statement.setString(1, MailOutboxStatus.RETRY.name)
                statement.setLong(2, timestamp)
                statement.setLong(3, timestamp)
                statement.setString(4, MailOutboxStatus.PROCESSING.name)
                return statement.executeUpdate()
            }
        }
    }

    @Synchronized
    fun find(
        accountKey: String,
        uidValidity: Long,
        uid: Long,
        operation: MailOutboxOperation = MailOutboxOperation.SEND_NOTIFICATION,
    ): MailOutboxItem? {
        val sql =
            """
            SELECT
                id,
                account_key,
                account_code,
                uid_validity,
                uid,
                operation,
                status,
                attempts,
                next_attempt_at,
                locked_at,
                telegram_message_id,
                last_error,
                created_at,
                updated_at
            FROM mail_notification_outbox
            WHERE account_key = ?
              AND uid_validity = ?
              AND uid = ?
              AND operation = ?
            """.trimIndent()

        openConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, accountKey)
                statement.setLong(2, uidValidity)
                statement.setLong(3, uid)
                statement.setString(4, operation.name)

                statement.executeQuery().use { result ->
                    return if (result.next()) {
                        result.toOutboxItem()
                    } else {
                        null
                    }
                }
            }
        }
    }

    @Synchronized
    fun countByStatus(status: MailOutboxStatus): Long {
        val sql =
            """
            SELECT COUNT(*) AS item_count
            FROM mail_notification_outbox
            WHERE status = ?
            """.trimIndent()

        openConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, status.name)
                statement.executeQuery().use { result ->
                    check(result.next())
                    return result.getLong("item_count")
                }
            }
        }
    }

    private fun updateTerminalState(
        id: Long,
        status: MailOutboxStatus,
        telegramMessageId: Long?,
        lastError: String?,
        incrementAttempts: Boolean,
        now: Instant,
    ) {
        require(status == MailOutboxStatus.SENT || status == MailOutboxStatus.DEAD)

        val attemptUpdate = if (incrementAttempts) {
            "attempts = attempts + 1,"
        } else {
            ""
        }

        val sql =
            """
            UPDATE mail_notification_outbox
            SET
                status = ?,
                $attemptUpdate
                locked_at = NULL,
                telegram_message_id = ?,
                last_error = ?,
                updated_at = ?
            WHERE id = ?
              AND status = ?
            """.trimIndent()

        openConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, status.name)

                if (telegramMessageId == null) {
                    statement.setNull(2, java.sql.Types.BIGINT)
                } else {
                    statement.setLong(2, telegramMessageId)
                }

                statement.setString(3, lastError)
                statement.setLong(4, now.toEpochMilli())
                statement.setLong(5, id)
                statement.setString(6, MailOutboxStatus.PROCESSING.name)

                check(statement.executeUpdate() == 1) {
                    "Outbox item $id is not in PROCESSING state"
                }
            }
        }
    }

    private fun openConnection(): Connection {
        return DriverManager.getConnection(databaseUrl).apply {
            createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 5000")
                statement.execute("PRAGMA foreign_keys = ON")
            }
        }
    }

    private fun ResultSet.toOutboxItem(): MailOutboxItem {
        val lockedAtMillis = getLong("locked_at")
        val lockedAt = if (wasNull()) {
            null
        } else {
            Instant.ofEpochMilli(lockedAtMillis)
        }

        val telegramMessageIdValue = getLong("telegram_message_id")
        val telegramMessageId = if (wasNull()) {
            null
        } else {
            telegramMessageIdValue
        }

        return MailOutboxItem(
            id = getLong("id"),
            accountKey = getString("account_key"),
            accountCode = getString("account_code"),
            uidValidity = getLong("uid_validity"),
            uid = getLong("uid"),
            operation = MailOutboxOperation.valueOf(
                getString("operation"),
            ),
            status = MailOutboxStatus.valueOf(
                getString("status"),
            ),
            attempts = getInt("attempts"),
            nextAttemptAt = Instant.ofEpochMilli(
                getLong("next_attempt_at"),
            ),
            lockedAt = lockedAt,
            telegramMessageId = telegramMessageId,
            lastError = getString("last_error"),
            createdAt = Instant.ofEpochMilli(
                getLong("created_at"),
            ),
            updatedAt = Instant.ofEpochMilli(
                getLong("updated_at"),
            ),
        )
    }

    private fun sanitizeError(error: String): String {
        return error
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .ifBlank { "Unknown error" }
            .take(MAX_ERROR_LENGTH)
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 500
    }
}
