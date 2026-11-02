package io.github.lightsmit.storage

import io.github.lightsmit.i18n.Lang
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Все, кто хоть раз писал боту или открывал приложение: для статистики, рассылок и языка интерфейса. */
class BotUserRepository {

    /** Язык пользователя и выбран ли он вручную (тогда язык Telegram его не меняет). */
    private data class LanguageChoice(val lang: Lang, val manual: Boolean)

    // Язык нужен при каждом уведомлении о письме: держим в памяти
    private val languages = ConcurrentHashMap<Long, LanguageChoice>()

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

            // Язык интерфейса (добавлен позже): из Telegram или выбранный вручную (/language)
            val columns = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(bot_users)").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString("name")) }
                }
            }
            if ("language" !in columns) {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("ALTER TABLE bot_users ADD COLUMN language TEXT")
                }
            }
            if ("language_manual" !in columns) {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("ALTER TABLE bot_users ADD COLUMN language_manual INTEGER NOT NULL DEFAULT 0")
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
                        language = CASE
                            WHEN bot_users.language_manual = 1 THEN bot_users.language
                            ELSE COALESCE(excluded.language, bot_users.language)
                        END
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
        if (language != null) {
            // Пока выбор не загружен из базы, не угадываем: возможно, язык выбран вручную
            languages.computeIfPresent(userId) { _, current ->
                if (current.manual) current else LanguageChoice(language, manual = false)
            }
        }
    }

    /** Язык, выбранный пользователем вручную: дальше язык Telegram его не меняет. */
    suspend fun setLanguage(userId: Long, lang: Lang, now: Instant = Instant.now()) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO bot_users (user_id, first_seen_at, last_seen_at, language, language_manual) VALUES (?, ?, ?, ?, 1)
                    ON CONFLICT(user_id) DO UPDATE SET language = excluded.language, language_manual = 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setLong(2, now.toEpochMilli())
                    statement.setLong(3, now.toEpochMilli())
                    statement.setString(4, lang.name)
                    statement.executeUpdate()
                }
            }
        }
        languages[userId] = LanguageChoice(lang, manual = true)
    }

    /** Язык интерфейса пользователя: выбранный вручную, иначе последний известный из Telegram, по умолчанию русский. */
    suspend fun language(userId: Long): Lang = choice(userId).lang

    /**
     * Язык для ответа на конкретное сообщение: ручной выбор важнее всего,
     * затем язык из этого сообщения Telegram, затем сохраненный.
     */
    suspend fun resolve(userId: Long, languageCode: String?): Lang {
        val stored = choice(userId)
        if (stored.manual) return stored.lang
        return languageCode?.takeIf(String::isNotBlank)?.let(Lang::fromCode) ?: stored.lang
    }

    private suspend fun choice(userId: Long): LanguageChoice {
        languages[userId]?.let { cached -> return cached }
        val loaded = withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT language, language_manual FROM bot_users WHERE user_id = ?").use { statement ->
                    statement.setLong(1, userId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            val lang = rs.getString(1)?.let { name -> runCatching { Lang.valueOf(name) }.getOrNull() } ?: Lang.RU
                            LanguageChoice(lang, manual = rs.getInt(2) == 1)
                        } else {
                            LanguageChoice(Lang.RU, manual = false)
                        }
                    }
                }
            }
        }
        languages[userId] = loaded
        return loaded
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
