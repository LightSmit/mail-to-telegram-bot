package io.github.lightsmit.storage

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.config.MailFilters
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

            // Фильтры писем (добавлены позже: дописываем колонки в существующую таблицу)
            ensureColumn(connection, "paused", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(connection, "skip_bulk", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(connection, "blocked_senders", "TEXT NOT NULL DEFAULT ''")
            ensureColumn(connection, "blocked_subject_words", "TEXT NOT NULL DEFAULT ''")
            ensureColumn(connection, "allowed_senders", "TEXT NOT NULL DEFAULT ''")
        }
    }

    private fun ensureColumn(connection: Connection, column: String, definition: String) {
        val exists = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(mail_accounts)").use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("name") == column) found = true
                }
                found
            }
        }
        if (!exists) {
            connection.createStatement().use { statement ->
                statement.executeUpdate("ALTER TABLE mail_accounts ADD COLUMN $column $definition")
            }
        }
    }

    /** Сохраняет фильтры ящика владельца. Возвращает обновленный ящик или null, если он не найден или чужой. */
    suspend fun updateFilters(id: Long, ownerId: Long, filters: MailFilters): MailAccountConfig? =
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                val updated = connection.prepareStatement(
                    """
                    UPDATE mail_accounts
                    SET paused = ?, skip_bulk = ?, blocked_senders = ?, blocked_subject_words = ?, allowed_senders = ?
                    WHERE id = ? AND owner_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, if (filters.paused) 1 else 0)
                    statement.setInt(2, if (filters.skipBulk) 1 else 0)
                    statement.setString(3, filters.blockedSenders.joinToString("\n"))
                    statement.setString(4, filters.blockedSubjectWords.joinToString("\n"))
                    statement.setString(5, filters.allowedSenders.joinToString("\n"))
                    statement.setLong(6, id)
                    statement.setLong(7, ownerId)
                    statement.executeUpdate()
                }
                if (updated == 0) return@withContext null

                connection.prepareStatement("SELECT * FROM mail_accounts WHERE id = ?").use { statement ->
                    statement.setLong(1, id)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toAccountOrNull() else null }
                }
            }
        }

    /** Ящик владельца по id; null - нет такого или он чужой. */
    suspend fun findByIdAndOwner(id: Long, ownerId: Long): MailAccountConfig? =
        withContext(DatabaseManager.dispatcher) {
            query("SELECT * FROM mail_accounts WHERE id = ? AND owner_id = ?") {
                setLong(1, id)
                setLong(2, ownerId)
            }.firstOrNull()
        }

    /** Меняет пароль приложения (хранится зашифрованным). Фильтры и остальное не трогаются. */
    suspend fun updatePassword(id: Long, ownerId: Long, password: String): MailAccountConfig? =
        withContext(DatabaseManager.dispatcher) {
            val updated = DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE mail_accounts SET password_encrypted = ? WHERE id = ? AND owner_id = ?",
                ).use { statement ->
                    statement.setString(1, CryptoHelper.encrypt(password))
                    statement.setLong(2, id)
                    statement.setLong(3, ownerId)
                    statement.executeUpdate()
                }
            }
            if (updated == 0) {
                null
            } else {
                query("SELECT * FROM mail_accounts WHERE id = ? AND owner_id = ?") {
                    setLong(1, id)
                    setLong(2, ownerId)
                }.firstOrNull()
            }
        }

    suspend fun findAll(): List<MailAccountConfig> = withContext(DatabaseManager.dispatcher) {
        query("SELECT * FROM mail_accounts ORDER BY id") { }
    }

    suspend fun findAllByOwnerId(ownerId: Long): List<MailAccountConfig> = withContext(DatabaseManager.dispatcher) {
        query("SELECT * FROM mail_accounts WHERE owner_id = ? ORDER BY id") { setLong(1, ownerId) }
    }

    /** Сколько разных пользователей подключили хотя бы один ящик. */
    suspend fun countOwners(): Long = withContext(DatabaseManager.dispatcher) {
        DatabaseManager.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(DISTINCT owner_id) FROM mail_accounts").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0
                }
            }
        }
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
     * Возвращает сохраненную запись с id и владельцем.
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

    /** Удаляет ящик владельца. Возвращает удаленную запись или null, если ее нет (или она чужая). */
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
    private fun String?.splitLines(): List<String> =
        this.orEmpty().split('\n').map(String::trim).filter(String::isNotEmpty)

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
                filters = MailFilters(
                    paused = getInt("paused") == 1,
                    skipBulk = getInt("skip_bulk") == 1,
                    blockedSenders = getString("blocked_senders").splitLines(),
                    blockedSubjectWords = getString("blocked_subject_words").splitLines(),
                    allowedSenders = getString("allowed_senders").splitLines(),
                ),
            )
        } catch (exception: Exception) {
            logger.warn("Skipping mail account {}: cannot decrypt password ({})", id, exception.javaClass.simpleName)
            null
        }
    }
}
