package io.github.lightsmit.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.Path

object DatabaseManager {
    val dispatcher = Dispatchers.IO.limitedParallelism(1)

    private var currentDataSource: HikariDataSource? = null

    val dataSource: HikariDataSource
        get() = currentDataSource
            ?: error("DatabaseManager is not initialized: call DatabaseManager.init() first")

    @Synchronized
    fun init(databasePath: Path) {
        if (currentDataSource != null) return

        val absolutePath = databasePath.toAbsolutePath().normalize()
        absolutePath.parent?.let(Files::createDirectories)

        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$absolutePath"
            maximumPoolSize = 1
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("synchronous", "NORMAL")
            addDataSourceProperty("busy_timeout", "5000")
            addDataSourceProperty("foreign_keys", "ON")
        }

        currentDataSource = HikariDataSource(config)
    }

    /** Закрывает пул соединений. Нужен для корректной остановки и для изоляции тестов. */
    @Synchronized
    fun close() {
        currentDataSource?.close()
        currentDataSource = null
    }
}
