package io.github.lightsmit.storage

import kotlinx.coroutines.withContext

data class MailAccountState(
    val uidValidity: Long,
    val lastUid: Long,
)

class MailStateRepository {

    init {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS mail_account_state (
                        account_key TEXT PRIMARY KEY,
                        uid_validity INTEGER NOT NULL,
                        last_uid INTEGER NOT NULL,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    suspend fun find(accountKey: String): MailAccountState? = withContext(DatabaseManager.dispatcher) {
        val sql = """
            SELECT uid_validity, last_uid
            FROM mail_account_state
            WHERE account_key = ?
        """.trimIndent()

        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, accountKey)

                statement.executeQuery().use { result ->
                    if (!result.next()) return@withContext null

                    MailAccountState(
                        uidValidity = result.getLong("uid_validity"),
                        lastUid = result.getLong("last_uid"),
                    )
                }
            }
        }
    }

    suspend fun save(
        accountKey: String,
        uidValidity: Long,
        lastUid: Long,
    ) = withContext(DatabaseManager.dispatcher) {
        val sql = """
            INSERT INTO mail_account_state (
                account_key,
                uid_validity,
                last_uid,
                updated_at
            )
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(account_key) DO UPDATE SET
                uid_validity = excluded.uid_validity,
                last_uid = excluded.last_uid,
                updated_at = CURRENT_TIMESTAMP
        """.trimIndent()

        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, accountKey)
                statement.setLong(2, uidValidity)
                statement.setLong(3, lastUid)
                statement.executeUpdate()
            }
        }
    }
}