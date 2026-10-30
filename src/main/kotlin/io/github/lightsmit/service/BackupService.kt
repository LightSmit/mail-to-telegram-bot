package io.github.lightsmit.service

import io.github.lightsmit.mail.EmailAttachment
import io.github.lightsmit.storage.DatabaseManager
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

data class BackupResult(
    val file: Path,
    val sizeBytes: Long,
    val createdAt: Instant,
)

/**
 * Ежедневная копия базы: согласованный снимок через VACUUM INTO (бот при этом
 * продолжает работать), сжатие, хранение нескольких последних копий на сервере
 * и отправка копии администраторам в Telegram - на случай потери самого сервера.
 *
 * Пароли ящиков в копии зашифрованы ENCRYPTION_KEY, сам ключ в копию не попадает.
 */
class BackupService(
    private val backupDirectory: Path,
    private val telegramClient: TelegramClient,
    private val adminChatIds: Set<Long>,
    private val dailyHour: Int,
    private val keepCopies: Int,
    private val sendToAdmins: Boolean,
) {
    private val logger = LoggerFactory.getLogger(BackupService::class.java)
    private val mutex = Mutex()
    private val fileTime = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

    @Volatile
    var lastBackup: BackupResult? = null
        private set

    init {
        require(dailyHour in 0..23) { "BACKUP_HOUR must be between 0 and 23" }
        require(keepCopies in 1..365) { "BACKUP_KEEP must be between 1 and 365" }
    }

    /** Бесконечный цикл: ждет BACKUP_HOUR по времени сервера и делает копию. */
    suspend fun runSchedule() {
        while (currentCoroutineContext().isActive) {
            delay(millisUntilNextRun())
            try {
                backupNow(notifyAdmins = sendToAdmins)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.error("Scheduled backup failed", exception)
                notifyAdminsText("⚠️ Не удалось сделать бэкап базы: ${exception.javaClass.simpleName}. Подробности в логе сервера.")
            }
        }
    }

    suspend fun backupNow(notifyAdmins: Boolean): BackupResult = mutex.withLock {
        Files.createDirectories(backupDirectory)
        val createdAt = Instant.now()
        val stamp = LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault()).format(fileTime)
        val snapshot = backupDirectory.resolve("mail-bot-$stamp.db")
        val compressed = backupDirectory.resolve("mail-bot-$stamp.db.gz")

        Files.deleteIfExists(snapshot)

        // VACUUM INTO делает согласованную копию живой базы, в том числе в режиме WAL
        withContext(DatabaseManager.dispatcher) {
            DatabaseManager.dataSource.connection.use { connection ->
                connection.prepareStatement("VACUUM INTO ?").use { statement ->
                    statement.setString(1, snapshot.toAbsolutePath().toString())
                    statement.execute()
                }
            }
        }

        withContext(Dispatchers.IO) {
            GZIPOutputStream(Files.newOutputStream(compressed)).use { output ->
                Files.copy(snapshot, output)
            }
            Files.deleteIfExists(snapshot)
            removeOldCopies()
        }

        val result = BackupResult(compressed, Files.size(compressed), createdAt)
        lastBackup = result
        logger.info("Database backup created: {} ({} bytes)", compressed.fileName, result.sizeBytes)

        if (notifyAdmins) {
            sendToAdmins(result)
        }

        result
    }

    private fun removeOldCopies() {
        val copies = Files.list(backupDirectory).use { paths ->
            paths
                .filter { path -> path.fileName.toString().matches(Regex("mail-bot-.*\\.db\\.gz")) }
                .sorted(Comparator.reverseOrder())
                .toList()
        }
        copies.drop(keepCopies).forEach { old -> runCatching { Files.deleteIfExists(old) } }
    }

    private suspend fun sendToAdmins(result: BackupResult) {
        for (chatId in adminChatIds) {
            try {
                telegramClient.sendAttachment(
                    chatId = chatId,
                    attachment = EmailAttachment(
                        fileName = result.file.fileName.toString(),
                        contentType = "application/gzip",
                        sizeBytes = result.sizeBytes,
                        tempFile = result.file,
                    ),
                )
                telegramClient.sendMessage(
                    chatId = chatId,
                    text = "🗄 Бэкап базы: ${result.file.fileName}, ${result.sizeBytes / 1024} КБ.\n" +
                        "Пароли ящиков в нем зашифрованы: без ENCRYPTION_KEY их не прочитать. Ключ храните отдельно.",
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn("Failed to send backup to admin {}: {}", chatId, exception.javaClass.simpleName)
            }
        }
    }

    private suspend fun notifyAdminsText(text: String) {
        for (chatId in adminChatIds) {
            runCatching { telegramClient.sendMessage(chatId = chatId, text = text) }
        }
    }

    private fun millisUntilNextRun(): Long {
        val now = ZonedDateTime.now()
        var next = now.withHour(dailyHour).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}
