package io.github.lightsmit.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class TelegramUpdateStateRepository(
    databasePath: Path,
) {

    private val databaseUrl: String

    init {
        val absolutePath = databasePath
            .toAbsolutePath()
            .normalize()

        absolutePath.parent?.let { parent ->
            Files.createDirectories(parent)
        }

        databaseUrl = "jdbc:sqlite:$absolutePath"

        DriverManager
            .getConnection(databaseUrl)
            .use { connection ->
                connection
                    .createStatement()
                    .use { statement ->
                        statement.executeUpdate(
                            """
                            CREATE TABLE IF NOT EXISTS telegram_update_state (
                                singleton_id INTEGER PRIMARY KEY,
                                next_offset INTEGER NOT NULL,
                                updated_at TEXT NOT NULL
                                    DEFAULT CURRENT_TIMESTAMP,
                                CHECK (singleton_id = 1)
                            )
                            """.trimIndent(),
                        )
                    }
            }
    }

    @Synchronized
    fun findNextOffset(): Long? {
        val sql =
            """
            SELECT next_offset
            FROM telegram_update_state
            WHERE singleton_id = 1
            """.trimIndent()

        DriverManager
            .getConnection(databaseUrl)
            .use { connection ->
                connection
                    .prepareStatement(sql)
                    .use { statement ->
                        statement
                            .executeQuery()
                            .use { result ->
                                if (!result.next()) {
                                    return null
                                }

                                return result.getLong(
                                    "next_offset",
                                )
                            }
                    }
            }
    }

    @Synchronized
    fun saveNextOffset(
        nextOffset: Long,
    ) {
        require(nextOffset >= 0) {
            "Telegram update offset must not be negative"
        }

        val sql =
            """
            INSERT INTO telegram_update_state (
                singleton_id,
                next_offset,
                updated_at
            )
            VALUES (
                1,
                ?,
                CURRENT_TIMESTAMP
            )
            ON CONFLICT(singleton_id) DO UPDATE SET
                next_offset = excluded.next_offset,
                updated_at = CURRENT_TIMESTAMP
            """.trimIndent()

        DriverManager
            .getConnection(databaseUrl)
            .use { connection ->
                connection
                    .prepareStatement(sql)
                    .use { statement ->
                        statement.setLong(
                            1,
                            nextOffset,
                        )

                        statement.executeUpdate()
                    }
            }
    }
}