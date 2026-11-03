package io.github.lightsmit.telegram

data class TelegramCallbackQueryUpdate(
    val updateId: Long,
    val callbackQueryId: String,
    val fromUserId: Long,
    val chatId: Long?,
    val messageId: Long?,
    val data: String?,
    // Язык Telegram пользователя ("ru", "en"...), если Telegram его передал
    val languageCode: String? = null,
)

/** Текстовое сообщение пользователя боту (например, /start). */
data class TelegramMessageUpdate(
    val updateId: Long,
    val chatId: Long,
    val chatType: String?,
    val fromUserId: Long,
    val text: String?,
    val languageCode: String? = null,
    // @username и имя: для списка пользователей у администратора
    val username: String? = null,
    val firstName: String? = null,
    // Сообщение об успешной оплате звездами (вместо текста)
    val payment: TelegramSuccessfulPayment? = null,
)

/** Успешная оплата (message.successful_payment). Для ежемесячной подписки приходит при каждом продлении. */
data class TelegramSuccessfulPayment(
    val currency: String,
    val totalAmount: Int,
    val payload: String?,
    val chargeId: String,
    // Unix-время окончания оплаченного периода подписки
    val subscriptionExpirationDate: Long? = null,
    val recurring: Boolean = false,
    val firstRecurring: Boolean = false,
)

/** Запрос подтверждения перед оплатой: боту нужно ответить в течение 10 секунд. */
data class TelegramPreCheckoutQuery(
    val updateId: Long,
    val id: String,
    val fromUserId: Long,
    val currency: String,
    val totalAmount: Int,
    val payload: String?,
    val languageCode: String? = null,
)

data class TelegramUpdateBatch(
    val nextOffset: Long?,
    val callbackQueries: List<TelegramCallbackQueryUpdate>,
    val messages: List<TelegramMessageUpdate> = emptyList(),
    val preCheckoutQueries: List<TelegramPreCheckoutQuery> = emptyList(),
)
