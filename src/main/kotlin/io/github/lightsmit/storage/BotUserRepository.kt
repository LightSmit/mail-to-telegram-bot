package io.github.lightsmit.storage

import io.github.lightsmit.i18n.Lang
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Все, кто хоть раз писал боту или открывал приложение: для статистики, рассылок и языка интерфейса. */
class BotUserRepository {

    // Язык нужен при каждом уведомлении о письме: держим в памяти
    private val languages = ConcurrentHashMap<Long, Lang>()

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

            // Язык Telegram пользователя (добавлен позже)
            val hasLanguage = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(bot_users)").use { rs ->
                    var found = false
                    while (rs.next()) if (rs.getString("name") == "language") found = true
                    found
                }
            }
            if (!hasLanguage) {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("ALTER TABLE bot_users ADD COLUMN language TEXT")
                }
            }
        }
    }

    /** Отмечает, что пользователь был активен (и снимает пометку "заблокировал бота"). */
    suspend fun touch(userId: Long, languageCode: String? = null, now: Instant = Instant.now()) {
        val language = languageCode?.takeIf(String::isNotBlank)?.let(Lang::fromCode)
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO bot_users (user_id, first_seen_at, last_seen_at, language) VALUES (?, ?, ?, ?)
                    ON CONFLICT(user_id) DO UPDATE SET
                        last_seen_at = excluded.last_seen_at,
                        blocked = 0,
                        language = COALESCE(excluded.language, bot_users.language)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setLong(2, now.toEpochMilli())
                    statement.setLong(3, now.toEpochMilli())
                    statement.setString(4, language?.name)
                    statement.executeUpdate()
                }
            }
        }
        language?.let { value -> languages[userId] = value }
    }

    /** Язык интерфейса пользователя: последний известный из Telegram, по умолчанию русский. */
    suspend fun language(userId: Long): Lang {
        languages[userId]?.let { language -> return language }
        val stored = withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT language FROM bot_users WHERE user_id = ?").use { statement ->
                    statement.setLong(1, userId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }
        }
        val language = stored?.let { name -> runCatching { Lang.valueOf(name) }.getOrNull() } ?: Lang.RU
        languages[userId] = language
        return language
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

    suspend fun delete(userId: Long) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM bot_users WHERE user_id = ?").use { statement ->
                    statement.setLong(1, userId)
                    statement.executeUpdate()
                }
            }
        }
        languages.remove(userId)
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
