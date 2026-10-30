package io.github.lightsmit.storage

import kotlinx.coroutines.withContext
import java.time.Instant

/** Все, кто хоть раз писал боту или открывал приложение: для статистики и рассылок. */
class BotUserRepository {

    init {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS bot_users (
                        user_id INTEGER PRIMARY KEY,
                        first_seen_at INTEGER NOT NULL,
                        last_seen_at INTEGER NOT NULL,
                        blocked INTEGER NOT NULL DEFAULT 0 CHECK (blocked IN (0, 1))
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    /** Отмечает, что пользователь был активен (и снимает пометку "заблокировал бота"). */
    suspend fun touch(userId: Long, now: Instant = Instant.now()) = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO bot_users (user_id, first_seen_at, last_seen_at) VALUES (?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET last_seen_at = excluded.last_seen_at, blocked = 0
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, now.toEpochMilli())
                statement.setLong(3, now.toEpochMilli())
                statement.executeUpdate()
            }
        }
    }

    /** Заносит владельцев ящиков, которых еще нет в таблице (они пришли до учета пользователей). */
    suspend fun backfillFromMailboxOwners(now: Instant = Instant.now()): Int = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO bot_users (user_id, first_seen_at, last_seen_at)
                SELECT DISTINCT owner_id, ?, ? FROM mail_accounts
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, now.toEpochMilli())
                statement.setLong(2, now.toEpochMilli())
                statement.executeUpdate()
            }
        }
    }

    suspend fun markBlocked(userId: Long) = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE bot_users SET blocked = 1 WHERE user_id = ?").use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
        }
    }

    suspend fun count(): Long = countWhere("1 = 1")

    suspend fun countSeenSince(since: Instant): Long = countWhere("last_seen_at >= ${since.toEpochMilli()}")

    suspend fun countFirstSeenSince(since: Instant): Long = countWhere("first_seen_at >= ${since.toEpochMilli()}")

    suspend fun countBlocked(): Long = countWhere("blocked = 1")

    /** Получатели рассылки: все, кто не заблокировал бота. */
    suspend fun findReachableIds(): List<Long> = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT user_id FROM bot_users WHERE blocked = 0 ORDER BY user_id").use { statement ->
                statement.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getLong(1)) }
                }
            }
        }
    }

    // Условия формируются только внутри этого класса из чисел, без пользовательского ввода
    private suspend fun countWhere(condition: String): Long = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM bot_users WHERE $condition").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0
                }
            }
        }
    }
}
