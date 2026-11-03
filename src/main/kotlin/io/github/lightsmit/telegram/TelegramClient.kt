package io.github.lightsmit.telegram

import io.github.lightsmit.mail.EmailAttachment
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class TelegramApiException(
    val description: String,
    val retryAfterSeconds: Int? = null,
    val errorCode: Int? = null,
    // Группа стала супергруппой: писать нужно в чат с этим ID
    val migrateToChatId: Long? = null,
) : RuntimeException(
    buildString {
        append("Telegram API")

        errorCode?.let { code ->
            append(" error ")
            append(code)
        }

        append(": ")
        append(description)
    },
)

class TelegramTransportException(
    operation: String,
    causeType: String,
) : RuntimeException("Telegram $operation failed: $causeType")

/** Кнопка под сообщением: ровно одно действие - callback, ссылка, копирование текста или Mini App. */
data class TelegramInlineButton(
    val text: String,
    val callbackData: String? = null,
    val url: String? = null,
    // Нажатие копирует этот текст в буфер обмена (например, код подтверждения)
    val copyText: String? = null,
    val webAppUrl: String? = null,
)

class TelegramClient(
    token: String,
    proxyUrl: String? = null,
) : AutoCloseable {

    private val baseUrl = "https://api.telegram.org/bot$token"

    private val httpClient = HttpClient(OkHttp) {
        expectSuccess = false

        install(HttpTimeout) {
            connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MILLIS
            requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS
        }

        engine {
            proxyUrl?.takeIf { it.isNotBlank() }?.let { value ->
                val url = io.ktor.http.Url(value)
                proxy = if (url.protocol.name == "socks") {
                    io.ktor.client.engine.ProxyBuilder.socks(host = url.host, port = url.port)
                } else {
                    io.ktor.client.engine.ProxyBuilder.http(url)
                }
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun sendMessage(
        chatId: Long,
        text: String,
    ): Long {
        val root = executeWithRetry("sendMessage") {
            httpClient.submitForm(
                url = "$baseUrl/sendMessage",
                formParameters = Parameters.build {
                    append("chat_id", chatId.toString())
                    append("text", text)
                },
            )
        }

        return extractMessageId(root)
    }

    suspend fun sendMessageWithButtons(
        chatId: Long,
        text: String,
        buttons: List<TelegramInlineButton>,
        // true - сообщение придет без звука (тихие часы, сводка)
        silent: Boolean = false,
        // Сколько кнопок в строке
        perRow: Int = 2,
    ): Long {
        require(buttons.isNotEmpty()) {
            "At least one Telegram button is required"
        }

        val replyMarkup = createReplyMarkup(buttons, perRow)
        val root = executeWithRetry("sendMessage") {
            httpClient.submitForm(
                url = "$baseUrl/sendMessage",
                formParameters = Parameters.build {
                    append("chat_id", chatId.toString())
                    append("text", text)
                    append("reply_markup", replyMarkup.toString())
                    if (silent) append("disable_notification", "true")
                },
            )
        }

        return extractMessageId(root)
    }

    /** Сообщение, после которого Telegram сразу открывает ответ на него (поле ввода с подсказкой). */
    suspend fun sendMessageForceReply(
        chatId: Long,
        text: String,
        placeholder: String,
    ): Long {
        val replyMarkup = buildJsonObject {
            put("force_reply", true)
            put("input_field_placeholder", placeholder.take(64))
        }
        val root = executeWithRetry("sendMessage") {
            httpClient.submitForm(
                url = "$baseUrl/sendMessage",
                formParameters = Parameters.build {
                    append("chat_id", chatId.toString())
                    append("text", text)
                    append("reply_markup", replyMarkup.toString())
                },
            )
        }

        return extractMessageId(root)
    }

    /** Регистрирует команды бота: Telegram показывает их подсказками при вводе "/". */
    suspend fun setMyCommands(
        commands: List<Pair<String, String>>,
        // null - команды по умолчанию, "en" - для пользователей с английским Telegram и т.д.
        languageCode: String? = null,
        // Команды только для одного чата (язык, выбранный пользователем вручную)
        chatId: Long? = null,
    ) {
        val payload = buildJsonArray {
            commands.forEach { (command, description) ->
                add(
                    buildJsonObject {
                        put("command", command)
                        put("description", description)
                    },
                )
            }
        }

        executeOnce("setMyCommands") {
            httpClient.submitForm(
                url = "$baseUrl/setMyCommands",
                formParameters = Parameters.build {
                    append("commands", payload.toString())
                    languageCode?.let { code -> append("language_code", code) }
                    chatId?.let { id -> append("scope", """{"type":"chat","chat_id":$id}""") }
                },
            )
        }
    }

    /** Сообщение с кнопкой, которая открывает Mini App прямо в Telegram (с подписанными данными). */
    suspend fun sendMessageWithWebAppButton(
        chatId: Long,
        text: String,
        buttonText: String,
        webAppUrl: String,
    ): Long {
        val replyMarkup = createReplyMarkup(listOf(TelegramInlineButton(text = buttonText, webAppUrl = webAppUrl)))

        val root = executeWithRetry("sendMessage") {
            httpClient.submitForm(
                url = "$baseUrl/sendMessage",
                formParameters = Parameters.build {
                    append("chat_id", chatId.toString())
                    append("text", text)
                    append("reply_markup", replyMarkup.toString())
                },
            )
        }

        return extractMessageId(root)
    }

    fun fitsSingleTextMessage(
        text: String,
    ): Boolean {
        return text.length <= TELEGRAM_MESSAGE_TEXT_LIMIT
    }

    suspend fun editMessage(
        chatId: Long,
        messageId: Long,
        text: String,
    ) {
        require(fitsSingleTextMessage(text)) {
            "Telegram message text exceeds the edit limit"
        }

        executeWithRetry("editMessageText") {
            httpClient.submitForm(
                url = "$baseUrl/editMessageText",
                formParameters = Parameters.build {
                    append("chat_id", chatId.toString())
                    append("message_id", messageId.toString())
                    append("text", text)
                },
            )
        }
    }

    suspend fun editMessageWithButtons(
        chatId: Long,
        messageId: Long,
        text: String,
        buttons: List<TelegramInlineButton>,
    ) {
        require(buttons.isNotEmpty()) {
            "At least one Telegram button is required"
        }

        require(fitsSingleTextMessage(text)) {
            "Telegram message text exceeds the edit limit"
        }

        val replyMarkup = createReplyMarkup(buttons)

        executeWithRetry("editMessageText") {
            httpClient.submitForm(
                url = "$baseUrl/editMessageText",
                formParameters = Parameters.build {
                    append("chat_id", chatId.toString())
                    append("message_id", messageId.toString())
                    append("text", text)
                    append("reply_markup", replyMarkup.toString())
                },
            )
        }
    }

    suspend fun sendLongMessage(
        chatId: Long,
        text: String,
    ): List<Long> {
        return splitText(text).map { part ->
            sendMessage(
                chatId = chatId,
                text = part,
            )
        }
    }

    suspend fun sendLongMessageWithButtons(
        chatId: Long,
        text: String,
        buttons: List<TelegramInlineButton>,
    ): List<Long> {
        val parts = splitText(text)
            .ifEmpty { listOf("—") }

        return parts.mapIndexed { index, part ->
            if (index == parts.lastIndex) {
                sendMessageWithButtons(
                    chatId = chatId,
                    text = part,
                    buttons = buttons,
                )
            } else {
                sendMessage(
                    chatId = chatId,
                    text = part,
                )
            }
        }
    }

    /**
     * Ссылка на оплату звездами (валюта XTR). С subscriptionPeriodSeconds - ежемесячная подписка:
     * Telegram сам списывает звезды каждые 30 дней. Ссылку можно отправлять кнопкой или открыть в Mini App.
     */
    suspend fun createStarsInvoiceLink(
        title: String,
        description: String,
        payload: String,
        label: String,
        amount: Int,
        subscriptionPeriodSeconds: Int? = null,
    ): String {
        val prices = buildJsonArray {
            add(
                buildJsonObject {
                    put("label", label)
                    put("amount", amount)
                },
            )
        }
        val root = executeWithRetry("createInvoiceLink") {
            httpClient.submitForm(
                url = "$baseUrl/createInvoiceLink",
                formParameters = Parameters.build {
                    append("title", title.take(32))
                    append("description", description.take(255))
                    append("payload", payload)
                    append("currency", "XTR")
                    append("prices", prices.toString())
                    subscriptionPeriodSeconds?.let { period -> append("subscription_period", period.toString()) }
                },
            )
        }
        return root["result"]?.jsonPrimitive?.contentOrNull
            ?: throw TelegramTransportException(operation = "read invoice link", causeType = "MissingResult")
    }

    /** Ответ на pre_checkout_query: ok=false отменяет оплату и показывает пользователю errorMessage. */
    suspend fun answerPreCheckoutQuery(
        queryId: String,
        ok: Boolean,
        errorMessage: String? = null,
    ) {
        executeOnce("answerPreCheckoutQuery") {
            httpClient.submitForm(
                url = "$baseUrl/answerPreCheckoutQuery",
                formParameters = Parameters.build {
                    append("pre_checkout_query_id", queryId)
                    append("ok", ok.toString())
                    if (!ok && errorMessage != null) append("error_message", errorMessage)
                },
            )
        }
    }

    /** Отменить (или вернуть) автопродление ежемесячной подписки Stars пользователя. */
    suspend fun editUserStarSubscription(
        userId: Long,
        chargeId: String,
        canceled: Boolean,
    ) {
        executeOnce("editUserStarSubscription") {
            httpClient.submitForm(
                url = "$baseUrl/editUserStarSubscription",
                formParameters = Parameters.build {
                    append("user_id", userId.toString())
                    append("telegram_payment_charge_id", chargeId)
                    append("is_canceled", canceled.toString())
                },
            )
        }
    }

    /** Вернуть пользователю звезды за оплату. */
    suspend fun refundStarPayment(
        userId: Long,
        chargeId: String,
    ) {
        executeOnce("refundStarPayment") {
            httpClient.submitForm(
                url = "$baseUrl/refundStarPayment",
                formParameters = Parameters.build {
                    append("user_id", userId.toString())
                    append("telegram_payment_charge_id", chargeId)
                },
            )
        }
    }

    /** Имя бота, которому принадлежит токен (getMe). Нужно для диагностики. */
    suspend fun getBotUsername(): String? {
        val root = executeOnce("getMe") {
            httpClient.get("$baseUrl/getMe")
        }
        return root["result"]
            ?.jsonObject
            ?.get("username")
            ?.jsonPrimitive
            ?.contentOrNull
    }

    suspend fun deleteMessage(
        chatId: Long,
        messageId: Long,
    ) {
        executeOnce("deleteMessage") {
            httpClient.submitForm(
                url = "$baseUrl/deleteMessage",
                formParameters = Parameters.build {
                    append("chat_id", chatId.toString())
                    append("message_id", messageId.toString())
                },
            )
        }
    }

    suspend fun getCallbackQueryUpdates(
        offset: Long?,
        timeoutSeconds: Int = 0,
    ): TelegramUpdateBatch {
        require(timeoutSeconds in 0..50) {
            "Telegram polling timeout must be between 0 and 50"
        }

        val pollingRequestTimeoutMillis = if (timeoutSeconds == 0) {
            SHORT_POLL_REQUEST_TIMEOUT_MILLIS
        } else {
            (timeoutSeconds + 15L) * 1_000L
        }

        val root = executeOnce("getUpdates") {
            httpClient.get("$baseUrl/getUpdates") {
                timeout {
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = pollingRequestTimeoutMillis
                    requestTimeoutMillis = pollingRequestTimeoutMillis
                }

                url {
                    offset?.let { currentOffset ->
                        parameters.append("offset", currentOffset.toString())
                    }
                    parameters.append("limit", "100")
                    if (timeoutSeconds > 0) {
                        parameters.append("timeout", timeoutSeconds.toString())
                    }
                    parameters.append(
                        "allowed_updates",
                        """["callback_query","message","pre_checkout_query","my_chat_member"]""",
                    )
                }
            }
        }

        val updates = root["result"]
            ?.jsonArray
            ?: return TelegramUpdateBatch(
                nextOffset = null,
                callbackQueries = emptyList(),
            )

        var highestUpdateId: Long? = null
        val callbackQueries = mutableListOf<TelegramCallbackQueryUpdate>()
        val messages = mutableListOf<TelegramMessageUpdate>()
        val preCheckoutQueries = mutableListOf<TelegramPreCheckoutQuery>()
        val chatMemberUpdates = mutableListOf<TelegramChatMemberUpdate>()

        for (updateElement in updates) {
            val update = updateElement.jsonObject
            val updateId = update["update_id"]
                ?.jsonPrimitive
                ?.longOrNull
                ?: continue

            highestUpdateId = maxOf(
                highestUpdateId ?: updateId,
                updateId,
            )

            val message = update["message"]?.jsonObject
            if (message != null) {
                val chat = message["chat"]?.jsonObject
                val chatId = chat?.get("id")?.jsonPrimitive?.longOrNull
                val from = message["from"]?.jsonObject
                val fromUserId = from?.get("id")?.jsonPrimitive?.longOrNull

                if (chatId != null && fromUserId != null) {
                    messages += TelegramMessageUpdate(
                        updateId = updateId,
                        chatId = chatId,
                        chatType = chat["type"]?.jsonPrimitive?.contentOrNull,
                        fromUserId = fromUserId,
                        text = message["text"]?.jsonPrimitive?.contentOrNull,
                        languageCode = from["language_code"]?.jsonPrimitive?.contentOrNull,
                        username = from["username"]?.jsonPrimitive?.contentOrNull,
                        firstName = from["first_name"]?.jsonPrimitive?.contentOrNull,
                        payment = message["successful_payment"]?.jsonObject?.let(::parseSuccessfulPayment),
                        chatTitle = chat["title"]?.jsonPrimitive?.contentOrNull,
                        migrateToChatId = message["migrate_to_chat_id"]?.jsonPrimitive?.longOrNull,
                        senderChatId = message["sender_chat"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull,
                    )
                }
                continue
            }

            val chatMember = update["my_chat_member"]?.jsonObject
            if (chatMember != null) {
                val chat = chatMember["chat"]?.jsonObject
                val chatId = chat?.get("id")?.jsonPrimitive?.longOrNull
                val fromUserId = chatMember["from"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
                val status = chatMember["new_chat_member"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
                if (chatId != null && fromUserId != null && status != null) {
                    chatMemberUpdates += TelegramChatMemberUpdate(
                        updateId = updateId,
                        chatId = chatId,
                        chatType = chat["type"]?.jsonPrimitive?.contentOrNull,
                        chatTitle = chat["title"]?.jsonPrimitive?.contentOrNull,
                        fromUserId = fromUserId,
                        newStatus = status,
                    )
                }
                continue
            }

            val preCheckout = update["pre_checkout_query"]?.jsonObject
            if (preCheckout != null) {
                val from = preCheckout["from"]?.jsonObject
                val queryId = preCheckout["id"]?.jsonPrimitive?.contentOrNull
                val fromUserId = from?.get("id")?.jsonPrimitive?.longOrNull
                if (queryId != null && fromUserId != null) {
                    preCheckoutQueries += TelegramPreCheckoutQuery(
                        updateId = updateId,
                        id = queryId,
                        fromUserId = fromUserId,
                        currency = preCheckout["currency"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        totalAmount = preCheckout["total_amount"]?.jsonPrimitive?.intOrNull ?: 0,
                        payload = preCheckout["invoice_payload"]?.jsonPrimitive?.contentOrNull,
                        languageCode = from["language_code"]?.jsonPrimitive?.contentOrNull,
                    )
                }
                continue
            }

            val callbackQuery = update["callback_query"]
                ?.jsonObject
                ?: continue

            val callbackQueryId = callbackQuery["id"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: continue

            val from = callbackQuery["from"]?.jsonObject
            val fromUserId = from
                ?.get("id")
                ?.jsonPrimitive
                ?.longOrNull
                ?: continue

            val sourceMessage = callbackQuery["message"]
                ?.jsonObject

            val chatId = sourceMessage
                ?.get("chat")
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.longOrNull

            val messageId = sourceMessage
                ?.get("message_id")
                ?.jsonPrimitive
                ?.longOrNull

            val data = callbackQuery["data"]
                ?.jsonPrimitive
                ?.contentOrNull

            callbackQueries += TelegramCallbackQueryUpdate(
                updateId = updateId,
                callbackQueryId = callbackQueryId,
                fromUserId = fromUserId,
                chatId = chatId,
                messageId = messageId,
                data = data,
                languageCode = from["language_code"]?.jsonPrimitive?.contentOrNull,
            )
        }

        return TelegramUpdateBatch(
            nextOffset = highestUpdateId?.plus(1),
            callbackQueries = callbackQueries,
            messages = messages,
            preCheckoutQueries = preCheckoutQueries,
            chatMemberUpdates = chatMemberUpdates,
        )
    }

    private fun parseSuccessfulPayment(payment: JsonObject): TelegramSuccessfulPayment? {
        val chargeId = payment["telegram_payment_charge_id"]?.jsonPrimitive?.contentOrNull ?: return null
        return TelegramSuccessfulPayment(
            currency = payment["currency"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            totalAmount = payment["total_amount"]?.jsonPrimitive?.intOrNull ?: 0,
            payload = payment["invoice_payload"]?.jsonPrimitive?.contentOrNull,
            chargeId = chargeId,
            subscriptionExpirationDate = payment["subscription_expiration_date"]?.jsonPrimitive?.longOrNull,
            recurring = payment["is_recurring"]?.jsonPrimitive?.booleanOrNull == true,
            firstRecurring = payment["is_first_recurring"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false,
    ) {
        executeOnce("answerCallbackQuery") {
            httpClient.submitForm(
                url = "$baseUrl/answerCallbackQuery",
                formParameters = Parameters.build {
                    append("callback_query_id", callbackQueryId)
                    text?.let { value -> append("text", value) }
                    append("show_alert", showAlert.toString())
                },
            )
        }
    }

    suspend fun sendAttachment(
        chatId: Long,
        attachment: EmailAttachment,
    ) {
        sendMultipartAttachment(
            operation = "sendDocument",
            endpoint = "sendDocument",
            fieldName = "document",
            chatId = chatId,
            attachment = attachment,
        )
    }

    private fun createReplyMarkup(
        buttons: List<TelegramInlineButton>,
        perRow: Int = 2,
    ): JsonObject {
        require(perRow in 1..8) {
            "Telegram buttons per row must be between 1 and 8"
        }

        buttons.forEach { button ->
            val actions = listOfNotNull(button.callbackData, button.url, button.copyText, button.webAppUrl)
            require(actions.size == 1) {
                "Telegram button must have exactly one action"
            }

            button.callbackData?.let { data ->
                require(data.toByteArray(Charsets.UTF_8).size in 1..64) {
                    "Telegram callback data must contain 1-64 bytes"
                }
            }
            button.copyText?.let { text ->
                require(text.length in 1..256) {
                    "Telegram copy text must contain 1-256 characters"
                }
            }
        }

        return buildJsonObject {
            put(
                "inline_keyboard",
                buildJsonArray {
                    // По умолчанию не больше двух кнопок в строке: на телефоне длинные подписи обрезаются
                    buttons.chunked(perRow).forEach { row ->
                        add(
                            buildJsonArray {
                                row.forEach { button ->
                                    add(
                                        buildJsonObject {
                                            put("text", button.text)
                                            button.callbackData?.let { data -> put("callback_data", data) }
                                            button.url?.let { url -> put("url", url) }
                                            button.copyText?.let { text ->
                                                put("copy_text", buildJsonObject { put("text", text) })
                                            }
                                            button.webAppUrl?.let { url ->
                                                put("web_app", buildJsonObject { put("url", url) })
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private suspend fun sendMultipartAttachment(
        operation: String,
        endpoint: String,
        fieldName: String,
        chatId: Long,
        attachment: EmailAttachment,
    ) {
        val safeFileName = attachment.fileName
            .replace('\\', '_')
            .replace('"', '_')
            .replace('\r', '_')
            .replace('\n', '_')

        executeWithRetry(operation) {
            httpClient.post("$baseUrl/$endpoint") {
                timeout {
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = MEDIA_TIMEOUT_MILLIS
                    requestTimeoutMillis = MEDIA_TIMEOUT_MILLIS
                }

                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("chat_id", chatId.toString())
                            append(
                                key = fieldName,
                                value = InputProvider(
                                    size = attachment.sizeBytes,
                                ) {
                                    attachment.tempFile
                                        .toFile()
                                        .inputStream()
                                        .asInput()
                                        .buffered()
                                },
                                headers = Headers.build {
                                    append(
                                        HttpHeaders.ContentType,
                                        attachment.contentType,
                                    )
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "filename=\"$safeFileName\"",
                                    )
                                },
                            )
                        },
                    ),
                )
            }
        }
    }

    private suspend fun executeOnce(
        operation: String,
        request: suspend () -> HttpResponse,
    ): JsonObject {
        return try {
            parseResponse(request().bodyAsText())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: TelegramApiException) {
            throw exception
        } catch (exception: Exception) {
            throw TelegramTransportException(
                operation = operation,
                causeType = exception.javaClass.simpleName,
            )
        }
    }

    private suspend fun executeWithRetry(
        operation: String,
        maxAttempts: Int = 4,
        request: suspend () -> HttpResponse,
    ): JsonObject {
        var lastFailureType = "unknown"

        for (attempt in 1..maxAttempts) {
            try {
                return parseResponse(request().bodyAsText())
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: TelegramApiException) {
                val retryAfter = exception.retryAfterSeconds
                if (retryAfter == null || attempt == maxAttempts) {
                    throw exception
                }

                delay(retryAfter.coerceIn(1, 60) * 1_000L)
            } catch (exception: Exception) {
                lastFailureType = exception.javaClass.simpleName

                if (attempt == maxAttempts) {
                    break
                }

                delay(retryDelayMillis(attempt))
            }
        }

        throw TelegramTransportException(
            operation = operation,
            causeType = lastFailureType,
        )
    }

    private fun parseResponse(body: String): JsonObject {
        val root = json
            .parseToJsonElement(body)
            .jsonObject

        val successful = root["ok"]
            ?.jsonPrimitive
            ?.booleanOrNull == true

        if (!successful) {
            val errorCode = root["error_code"]
                ?.jsonPrimitive
                ?.intOrNull

            val description = root["description"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: "Unknown Telegram API error"

            val retryAfter = root["parameters"]
                ?.jsonObject
                ?.get("retry_after")
                ?.jsonPrimitive
                ?.intOrNull

            val migrateTo = root["parameters"]
                ?.jsonObject
                ?.get("migrate_to_chat_id")
                ?.jsonPrimitive
                ?.longOrNull

            throw TelegramApiException(
                description = description,
                retryAfterSeconds = retryAfter,
                errorCode = errorCode,
                migrateToChatId = migrateTo,
            )
        }

        return root
    }

    private fun extractMessageId(root: JsonObject): Long {
        return root["result"]
            ?.jsonObject
            ?.get("message_id")
            ?.jsonPrimitive
            ?.longOrNull
            ?: throw TelegramTransportException(
                operation = "read message ID",
                causeType = "MissingMessageId",
            )
    }

    private fun splitText(
        text: String,
        maxLength: Int = 3_500,
    ): List<String> {
        require(maxLength > 0) {
            "Maximum message length must be greater than zero"
        }

        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) return emptyList()
        if (normalizedText.length <= maxLength) {
            return listOf(normalizedText)
        }

        val parts = mutableListOf<String>()
        var startIndex = 0

        while (startIndex < normalizedText.length) {
            var endIndex = minOf(
                startIndex + maxLength,
                normalizedText.length,
            )

            if (endIndex < normalizedText.length) {
                val newlineIndex = normalizedText.lastIndexOf(
                    char = '\n',
                    startIndex = endIndex - 1,
                )
                val spaceIndex = normalizedText.lastIndexOf(
                    char = ' ',
                    startIndex = endIndex - 1,
                )
                val preferredIndex = maxOf(
                    newlineIndex,
                    spaceIndex,
                )

                if (preferredIndex >= startIndex + maxLength / 2) {
                    endIndex = preferredIndex
                }

                if (
                    endIndex > startIndex &&
                    normalizedText[endIndex - 1].isHighSurrogate()
                ) {
                    endIndex--
                }
            }

            val part = normalizedText
                .substring(startIndex, endIndex)
                .trim()

            if (part.isNotEmpty()) {
                parts += part
            }

            startIndex = endIndex
            while (
                startIndex < normalizedText.length &&
                normalizedText[startIndex].isWhitespace()
            ) {
                startIndex++
            }
        }

        return parts
    }

    override fun close() {
        httpClient.close()
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L
        const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 90_000L
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 90_000L
        const val SHORT_POLL_REQUEST_TIMEOUT_MILLIS = 10_000L
        const val MEDIA_TIMEOUT_MILLIS = 300_000L
        const val TELEGRAM_MESSAGE_TEXT_LIMIT = 4_096

        fun retryDelayMillis(attempt: Int): Long {
            return when (attempt) {
                1 -> 1_000L
                2 -> 2_500L
                else -> 5_000L
            }
        }
    }
}
