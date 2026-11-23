package io.github.lightsmit.storage

import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Письма, пришедшие пересылкой на адрес бота. У ящика-пересылки нет IMAP, поэтому
 * исходное письмо хранится здесь несколько дней: из него строятся текст и вложения
 * по кнопкам под карточкой. Номер записи служит UID письма.
 */
class InboundMessageRepository {

    init {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS inbound_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        account_key TEXT NOT NULL,
                        message_key TEXT NOT NULL,
                        raw BLOB NOT NULL,
                        received_at INTEGER NOT NULL,
                        UNIQUE (account_key, message_key)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS inbound_messages_received ON inbound_messages (received_at)")
            }
        }
    }

    /**
     * Сохраняет письмо. Возвращает его UID или null, если это письмо уже приходило
     * (тот же Message-ID: почта иногда доставляет одно письмо дважды).
     */
    suspend fun insert(accountKey: String, messageKey: String, raw: ByteArray, now: Instant = Instant.now()): Long? =
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                val inserted = connection.prepareStatement(
                    "INSERT OR IGNORE INTO inbound_messages (account_key, message_key, raw, received_at) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, accountKey)
                    statement.setString(2, messageKey)
                    statement.setBytes(3, raw)
                    statement.setLong(4, now.toEpochMilli())
                    statement.executeUpdate() == 1
                }
                if (!inserted) return@use null

                connection.prepareStatement(
                    "SELECT id FROM inbound_messages WHERE account_key = ? AND message_key = ?",
                ).use { statement ->
                    statement.setString(1, accountKey)
                    statement.setString(2, messageKey)
                    statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
                }
            }
        }

    suspend fun find(accountKey: String, uid: Long): ByteArray? = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT raw FROM inbound_messages WHERE id = ? AND account_key = ?").use { statement ->
                statement.setLong(1, uid)
                statement.setString(2, accountKey)
                statement.executeQuery().use { result -> if (result.next()) result.getBytes(1) else null }
            }
        }
    }

    /** Удаляет письма старше before. Возвращает число удаленных. */
    suspend fun deleteOlderThan(before: Instant): Int = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM inbound_messages WHERE received_at < ?").use { statement ->
                statement.setLong(1, before.toEpochMilli())
                statement.executeUpdate()
            }
        }
    }

    suspend fun deleteAccount(accountKey: String) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM inbound_messages WHERE account_key = ?").use { statement ->
                    statement.setString(1, accountKey)
                    statement.executeUpdate()
                }
            }
        }
    }
}
