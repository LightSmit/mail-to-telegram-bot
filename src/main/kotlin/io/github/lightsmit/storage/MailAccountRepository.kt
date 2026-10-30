package io.github.lightsmit.storage

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.security.CryptoHelper
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet

class MailAccountRepository {

    private val logger = LoggerFactory.getLogger(MailAccountRepository::class.java)

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

    suspend fun findAll(): List<MailAccountConfig> = withContext(DatabaseManager.dispatcher) {
        query("SELECT * FROM mail_accounts ORDER BY id") { }
    }

    suspend fun findAllByOwnerId(ownerId: Long): List<MailAccountConfig> = withContext(DatabaseManager.dispatcher) {
        query("SELECT * FROM mail_accounts WHERE owner_id = ? ORDER BY id") { setLong(1, ownerId) }
    }

    suspend fun countByOwnerId(ownerId: Long): Int = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM mail_accounts WHERE owner_id = ?").use { statement ->
                statement.setLong(1, ownerId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    suspend fun existsForOwner(ownerId: Long, username: String): Boolean = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM mail_accounts WHERE owner_id = ? AND username = ?",
            ).use { statement ->
                statement.setLong(1, ownerId)
                statement.setString(2, username)
                statement.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    /**
     * Добавляет ящик или обновляет существующий (тот же владелец и логин).
     * Возвращает сохранённую запись с id и владельцем.
     */
    suspend fun upsert(ownerId: Long, config: MailAccountConfig): MailAccountConfig =
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO mail_accounts (owner_id, name, host, port, username, password_encrypted)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(owner_id, username) DO UPDATE SET
                        name = excluded.name,
                        host = excluded.host,
                        port = excluded.port,
                        password_encrypted = excluded.password_encrypted
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindAccount(ownerId, config)
                    statement.executeUpdate()
                }

                findByOwnerAndUsername(connection, ownerId, config.username)
                    ?: error("Saved mail account was not found")
            }
        }

    /** Добавляет ящик, только если у владельца ещё нет ящика с таким логином. */
    suspend fun insertIfAbsent(ownerId: Long, config: MailAccountConfig): Boolean =
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO mail_accounts (owner_id, name, host, port, username, password_encrypted)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(owner_id, username) DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindAccount(ownerId, config)
                    statement.executeUpdate() > 0
                }
            }
        }

    /** Удаляет ящик владельца. Возвращает удалённую запись или null, если её нет (или она чужая). */
    suspend fun delete(id: Long, ownerId: Long): MailAccountConfig? = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            val existing = connection.prepareStatement(
                "SELECT * FROM mail_accounts WHERE id = ? AND owner_id = ?",
            ).use { statement ->
                statement.setLong(1, id)
                statement.setLong(2, ownerId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.toAccountOrNull() else null }
            } ?: return@withContext null

            connection.prepareStatement("DELETE FROM mail_accounts WHERE id = ? AND owner_id = ?").use { statement ->
                statement.setLong(1, id)
                statement.setLong(2, ownerId)
                statement.executeUpdate()
            }

            existing
        }
    }

    private fun query(
        sql: String,
        bind: java.sql.PreparedStatement.() -> Unit,
    ): List<MailAccountConfig> {
        val accounts = mutableListOf<MailAccountConfig>()
        DatabaseManager.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.bind()
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        rs.toAccountOrNull()?.let(accounts::add)
                    }
                }
            }
        }
        return accounts
    }

    private fun findByOwnerAndUsername(connection: Connection, ownerId: Long, username: String): MailAccountConfig? =
        connection.prepareStatement(
            "SELECT * FROM mail_accounts WHERE owner_id = ? AND username = ?",
        ).use { statement ->
            statement.setLong(1, ownerId)
            statement.setString(2, username)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toAccountOrNull() else null }
        }

    private fun java.sql.PreparedStatement.bindAccount(ownerId: Long, config: MailAccountConfig) {
        setLong(1, ownerId)
        setString(2, config.name)
        setString(3, config.host)
        setInt(4, config.port)
        setString(5, config.username)
        setString(6, CryptoHelper.encrypt(config.password))
    }

    // Запись, которую не удалось расшифровать (например, после смены ENCRYPTION_KEY),
    // пропускаем с предупреждением, чтобы один ящик не ронял весь бот.
    private fun ResultSet.toAccountOrNull(): MailAccountConfig? {
        val id = getLong("id")
        return try {
            MailAccountConfig(
                name = getString("name"),
                host = getString("host"),
                port = getInt("port"),
                username = getString("username"),
                password = CryptoHelper.decrypt(getString("password_encrypted")),
                ownerChatId = getLong("owner_id"),
                id = id,
            )
        } catch (exception: Exception) {
            logger.warn("Skipping mail account {}: cannot decrypt password ({})", id, exception.javaClass.simpleName)
            null
        }
    }
}
