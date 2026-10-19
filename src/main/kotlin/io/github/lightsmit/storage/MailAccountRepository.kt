package io.github.lightsmit.storage

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.security.CryptoHelper
import kotlinx.coroutines.withContext

class MailAccountRepository {

    init {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS mail_accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        username TEXT NOT NULL,
                        password_encrypted TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (owner_id, username)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    suspend fun findAllByOwnerId(ownerId: Long): List<MailAccountConfig> = withContext(DatabaseManager.dispatcher) {
        val accounts = mutableListOf<MailAccountConfig>()
        val sql = "SELECT * FROM mail_accounts WHERE owner_id = ?"

        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, ownerId)
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        accounts.add(
                            MailAccountConfig(
                                name = rs.getString("name"),
                                host = rs.getString("host"),
                                port = rs.getInt("port"),
                                username = rs.getString("username"),
                                password = CryptoHelper.decrypt(rs.getString("password_encrypted"))
                            )
                        )
                    }
                }
            }
        }
        accounts
    }

    suspend fun save(ownerId: Long, config: MailAccountConfig) = withContext(DatabaseManager.dispatcher) {
        val sql = """
            INSERT OR REPLACE INTO mail_accounts (owner_id, name, host, port, username, password_encrypted)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()

        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, ownerId)
                statement.setString(2, config.name)
                statement.setString(3, config.host)
                statement.setInt(4, config.port)
                statement.setString(5, config.username)
                statement.setString(6, CryptoHelper.encrypt(config.password))
                statement.executeUpdate()
            }
        }
    }
}