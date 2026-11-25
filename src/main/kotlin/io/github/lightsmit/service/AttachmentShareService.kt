package io.github.lightsmit.service

import io.github.lightsmit.mail.EmailAttachment
import io.github.lightsmit.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * «Поделиться» вложением через Telegram (WebApp.shareMessage): файл один раз загружается
 * в Telegram (сообщение в чате с ботом сразу удаляется), дальше используется его file_id.
 * Telegram показывает свой выбор чата, файл уходит обычным документом.
 */
class AttachmentShareService(
    private val uploadClient: TelegramClient,
    private val controlClient: TelegramClient,
) {
    private val logger = LoggerFactory.getLogger(AttachmentShareService::class.java)

    // Вложение -> file_id: повторно файл не загружаем
    private val fileIds = ConcurrentHashMap<String, String>()

    /** id подготовленного сообщения для WebApp.shareMessage. */
    suspend fun prepare(userId: Long, key: String, attachment: EmailAttachment): String {
        val fileId = fileIds[key] ?: upload(userId, attachment).also { value ->
            if (fileIds.size > MAX_CACHED) fileIds.clear()
            fileIds[key] = value
        }
        return controlClient.savePreparedDocument(userId = userId, fileId = fileId, title = attachment.fileName)
    }

    private suspend fun upload(userId: Long, attachment: EmailAttachment): String {
        val uploaded = uploadClient.uploadDocument(chatId = userId, attachment = attachment)
        // Служебное сообщение не оставляем в чате: file_id работает и после удаления
        try {
            controlClient.deleteMessage(chatId = userId, messageId = uploaded.messageId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug("Failed to delete upload message for user {}: {}", userId, exception.javaClass.simpleName)
        }
        return uploaded.fileId
    }

    private companion object {
        const val MAX_CACHED = 5_000
    }
}
