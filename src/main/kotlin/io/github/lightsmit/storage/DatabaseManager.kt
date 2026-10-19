package io.github.lightsmit.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.Path

object DatabaseManager {
    val dispatcher = Dispatchers.IO.limitedParallelism(1)

    lateinit var dataSource: HikariDataSource
        private set

    fun init(databasePath: Path) {
        if (::dataSource.isInitialized) return

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

        dataSource = HikariDataSource(config)
    }
}