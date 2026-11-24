package io.github.lightsmit.storage

import kotlinx.coroutines.withContext
import java.time.Instant

/** Шаг воронки пользователя: каждый шаг записывается один раз, по первому разу. */
data class FunnelEvent(
    val userId: Long,
    val step: String,
    // Подробность шага: источник для start, почта для provider, причина для connect_error
    val detail: String?,
    val at: Instant,
)

interface FunnelStore {
    /** false - этот шаг у пользователя уже записан. */
    suspend fun insert(event: FunnelEvent): Boolean

    /** Все шаги пользователей, которые впервые запустили бота (шаг start) не раньше since. */
    suspend fun cohortEvents(since: Instant): List<FunnelEvent>

    /** Все шаги одного пользователя (карточка в админ-панели). */
    suspend fun eventsOf(userId: Long): List<FunnelEvent> = emptyList()

    /** Последние шаги всех пользователей, новые первыми (лента в админ-панели). */
    suspend fun recent(limit: Int): List<FunnelEvent> = emptyList()

    suspend fun deleteUser(userId: Long)
}

/** Воронка новых пользователей. Хранится только Telegram ID, шаг и время, без IP и устройств. */
class FunnelRepository : FunnelStore {

    init {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS funnel_events (
                        user_id INTEGER NOT NULL,
                        step TEXT NOT NULL,
                        detail TEXT,
                        at INTEGER NOT NULL,
                        PRIMARY KEY (user_id, step)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS funnel_events_step_at ON funnel_events (step, at)")
            }
        }
    }

    override suspend fun insert(event: FunnelEvent): Boolean = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT OR IGNORE INTO funnel_events (user_id, step, detail, at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setLong(1, event.userId)
                statement.setString(2, event.step)
                statement.setString(3, event.detail)
                statement.setLong(4, event.at.toEpochMilli())
                statement.executeUpdate() == 1
            }
        }
    }

    override suspend fun cohortEvents(since: Instant): List<FunnelEvent> = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT user_id, step, detail, at FROM funnel_events
                WHERE user_id IN (SELECT user_id FROM funnel_events WHERE step = 'start' AND at >= ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, since.toEpochMilli())
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                FunnelEvent(
                                    userId = rs.getLong("user_id"),
                                    step = rs.getString("step"),
                                    detail = rs.getString("detail"),
                                    at = Instant.ofEpochMilli(rs.getLong("at")),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun eventsOf(userId: Long): List<FunnelEvent> =
        select("SELECT user_id, step, detail, at FROM funnel_events WHERE user_id = ? ORDER BY at", userId)

    override suspend fun recent(limit: Int): List<FunnelEvent> =
        select("SELECT user_id, step, detail, at FROM funnel_events ORDER BY at DESC LIMIT ?", limit.toLong())

    private suspend fun select(sql: String, parameter: Long): List<FunnelEvent> = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, parameter)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                FunnelEvent(
                                    userId = rs.getLong("user_id"),
                                    step = rs.getString("step"),
                                    detail = rs.getString("detail"),
                                    at = Instant.ofEpochMilli(rs.getLong("at")),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun deleteUser(userId: Long) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM funnel_events WHERE user_id = ?").use { statement ->
                    statement.setLong(1, userId)
                    statement.executeUpdate()
                }
            }
        }
    }
}
