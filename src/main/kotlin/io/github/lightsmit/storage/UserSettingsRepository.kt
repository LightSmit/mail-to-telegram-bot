package io.github.lightsmit.storage

import io.github.lightsmit.service.NotificationSettings
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Настройки уведомлений пользователя (тихие часы, сводка). Читаются часто, поэтому с кэшем в памяти. */
class UserSettingsRepository {

    private val cache = ConcurrentHashMap<Long, NotificationSettings>()

    init {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS user_settings (
                        user_id INTEGER PRIMARY KEY,
                        timezone TEXT NOT NULL,
                        quiet_enabled INTEGER NOT NULL DEFAULT 0,
                        quiet_start_minutes INTEGER NOT NULL DEFAULT 1380,
                        quiet_end_minutes INTEGER NOT NULL DEFAULT 480,
                        digest_hours INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    suspend fun get(userId: Long): NotificationSettings {
        cache[userId]?.let { settings -> return settings }

        val loaded = withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT * FROM user_settings WHERE user_id = ?").use { statement ->
                    statement.setLong(1, userId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            NotificationSettings(
                                timezone = rs.getString("timezone"),
                                quietEnabled = rs.getInt("quiet_enabled") == 1,
                                quietStartMinutes = rs.getInt("quiet_start_minutes"),
                                quietEndMinutes = rs.getInt("quiet_end_minutes"),
                                digestHours = rs.getInt("digest_hours"),
                            )
                        } else {
                            NotificationSettings()
                        }
                    }
                }
            }
        }

        cache[userId] = loaded
        return loaded
    }

    suspend fun delete(userId: Long) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM user_settings WHERE user_id = ?").use { statement ->
                    statement.setLong(1, userId)
                    statement.executeUpdate()
                }
            }
        }
        cache.remove(userId)
    }

    suspend fun save(userId: Long, settings: NotificationSettings, now: Instant = Instant.now()) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO user_settings (user_id, timezone, quiet_enabled, quiet_start_minutes, quiet_end_minutes, digest_hours, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(user_id) DO UPDATE SET
                        timezone = excluded.timezone,
                        quiet_enabled = excluded.quiet_enabled,
                        quiet_start_minutes = excluded.quiet_start_minutes,
                        quiet_end_minutes = excluded.quiet_end_minutes,
                        digest_hours = excluded.digest_hours,
                        updated_at = excluded.updated_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setString(2, settings.timezone)
                    statement.setInt(3, if (settings.quietEnabled) 1 else 0)
                    statement.setInt(4, settings.quietStartMinutes)
                    statement.setInt(5, settings.quietEndMinutes)
                    statement.setInt(6, settings.digestHours)
                    statement.setLong(7, now.toEpochMilli())
                    statement.executeUpdate()
                }
            }
        }
        cache[userId] = settings
    }
}
