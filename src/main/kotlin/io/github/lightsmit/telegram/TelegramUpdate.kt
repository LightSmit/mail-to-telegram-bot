package io.github.lightsmit.telegram

data class TelegramCallbackQueryUpdate(
    val updateId: Long,
    val callbackQueryId: String,
    val fromUserId: Long,
    val chatId: Long?,
    val messageId: Long?,
    val data: String?,
)

data class TelegramUpdateBatch(
    val nextOffset: Long?,
    val callbackQueries: List<TelegramCallbackQueryUpdate>,
)
