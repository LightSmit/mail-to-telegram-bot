package io.github.lightsmit.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

data class MailAccountState(
    val uidValidity: Long,
    val lastUid: Long,
)

class MailStateRepository(
    databasePath: Path,
) {

    private val databaseUrl: String

    init {
        val absolutePath = databasePath
            .toAbsolutePath()
            .normalize()

        absolutePath.parent?.let(Files::createDirectories)

        databaseUrl = "jdbc:sqlite:$absolutePath"

        DriverManager.getConnection(databaseUrl).use { connection ->
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

    fun find(accountKey: String): MailAccountState? {
        val sql =
            """
            SELECT uid_validity, last_uid
            FROM mail_account_state
            WHERE account_key = ?
            """.trimIndent()

        DriverManager.getConnection(databaseUrl).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, accountKey)

                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        return null
                    }

                    return MailAccountState(
                        uidValidity = result.getLong("uid_validity"),
                        lastUid = result.getLong("last_uid"),
                    )
                }
            }
        }
    }

    fun save(
        accountKey: String,
        uidValidity: Long,
        lastUid: Long,
    ) {
        val sql =
            """
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

        DriverManager.getConnection(databaseUrl).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, accountKey)
                statement.setLong(2, uidValidity)
                statement.setLong(3, lastUid)
                statement.executeUpdate()
            }
        }
    }
}