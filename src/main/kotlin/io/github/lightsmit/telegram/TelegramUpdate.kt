package io.github.lightsmit.telegram

data class TelegramCallbackQueryUpdate(
    val updateId: Long,
    val callbackQueryId: String,
    val fromUserId: Long,
    val chatId: Long?,
    val messageId: Long?,
    val data: String?,
)

/** Текстовое сообщение пользователя боту (например, /start). */
data class TelegramMessageUpdate(
    val updateId: Long,
    val chatId: Long,
    val chatType: String?,
    val fromUserId: Long,
    val text: String?,
)

data class TelegramUpdateBatch(
    val nextOffset: Long?,
    val callbackQueries: List<TelegramCallbackQueryUpdate>,
    val messages: List<TelegramMessageUpdate> = emptyList(),
)
