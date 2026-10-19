package io.github.lightsmit.storage

import kotlinx.coroutines.withContext

class TelegramUpdateStateRepository {

    init {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS telegram_update_state (
                        singleton_id INTEGER PRIMARY KEY,
                        next_offset INTEGER NOT NULL,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CHECK (singleton_id = 1)
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    suspend fun findNextOffset(): Long? = withContext(DatabaseManager.dispatcher) {
        val sql = """
            SELECT next_offset
            FROM telegram_update_state
            WHERE singleton_id = 1
        """.trimIndent()

        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    if (!result.next()) return@withContext null
                    result.getLong("next_offset")
                }
            }
        }
    }

    suspend fun saveNextOffset(nextOffset: Long) = withContext(DatabaseManager.dispatcher) {
        require(nextOffset >= 0) { "Telegram update offset must not be negative" }

        val sql = """
            INSERT INTO telegram_update_state (
                singleton_id, next_offset, updated_at
            )
            VALUES (1, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(singleton_id) DO UPDATE SET
                next_offset = excluded.next_offset,
                updated_at = CURRENT_TIMESTAMP
        """.trimIndent()

        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, nextOffset)
                statement.executeUpdate()
            }
        }
    }
}