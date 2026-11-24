package io.github.lightsmit.storage

import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Письма, отмеченные звездочкой в приложении. Звездочки видит только сам пользователь:
 * в почтовом ящике ничего не меняется. Письмо определяют папка, UIDVALIDITY и UID.
 */
class StarredRepository {

    init {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS starred_messages (
                        owner_id INTEGER NOT NULL,
                        account_id INTEGER NOT NULL,
                        folder TEXT NOT NULL,
                        uid_validity INTEGER NOT NULL,
                        uid INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY (account_id, folder, uid_validity, uid)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS starred_messages_owner ON starred_messages (owner_id)")
            }
        }
    }

    suspend fun set(ownerId: Long, accountId: Long, folder: String, uidValidity: Long, uid: Long, starred: Boolean) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                val sql = if (starred) {
                    "INSERT OR IGNORE INTO starred_messages (owner_id, account_id, folder, uid_validity, uid, created_at) VALUES (?, ?, ?, ?, ?, ?)"
                } else {
                    "DELETE FROM starred_messages WHERE owner_id = ? AND account_id = ? AND folder = ? AND uid_validity = ? AND uid = ?"
                }
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, ownerId)
                    statement.setLong(2, accountId)
                    statement.setString(3, folder)
                    statement.setLong(4, uidValidity)
                    statement.setLong(5, uid)
                    if (starred) statement.setLong(6, Instant.now().toEpochMilli())
                    statement.executeUpdate()
                }
            }
        }
    }

    /** UID отмеченных писем папки при данном UIDVALIDITY, новые отметки первыми. */
    suspend fun uids(accountId: Long, folder: String, uidValidity: Long): List<Long> =
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT uid FROM starred_messages WHERE account_id = ? AND folder = ? AND uid_validity = ? ORDER BY created_at DESC",
                ).use { statement ->
                    statement.setLong(1, accountId)
                    statement.setString(2, folder)
                    statement.setLong(3, uidValidity)
                    statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
                }
            }
        }

    /** Все отметки папки по UIDVALIDITY: до открытия ящика он неизвестен. */
    suspend fun byValidity(accountId: Long, folder: String): Map<Long, List<Long>> =
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT uid_validity, uid FROM starred_messages WHERE account_id = ? AND folder = ? ORDER BY created_at DESC",
                ).use { statement ->
                    statement.setLong(1, accountId)
                    statement.setString(2, folder)
                    statement.executeQuery().use { rs ->
                        val result = LinkedHashMap<Long, MutableList<Long>>()
                        while (rs.next()) result.getOrPut(rs.getLong(1)) { mutableListOf() }.add(rs.getLong(2))
                        result
                    }
                }
            }
        }

    suspend fun deleteAccount(accountId: Long) = delete("DELETE FROM starred_messages WHERE account_id = ?", accountId)

    suspend fun deleteOwner(ownerId: Long) = delete("DELETE FROM starred_messages WHERE owner_id = ?", ownerId)

    private suspend fun delete(sql: String, id: Long) {
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, id)
                    statement.executeUpdate()
                }
            }
        }
    }
}
