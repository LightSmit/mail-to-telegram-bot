package io.github.lightsmit.telegram

import io.github.lightsmit.mail.EmailAttachment
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
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
) : RuntimeException(description)

class TelegramTransportException(
    operation: String,
    causeType: String,
) : RuntimeException("Telegram $operation failed: $causeType")

data class TelegramInlineButton(
    val text: String,
    val callbackData: String,
)

class TelegramClient(
    token: String,
    proxyUrl: String? = null,
) : AutoCloseable {

    private val baseUrl = "https://api.telegram.org/bot$token"

    private val httpClient = HttpClient(CIO) {
        expectSuccess = false

        install(HttpTimeout) {
            connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MILLIS
            requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS
        }

        engine {
            proxy = proxyUrl
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { value -> ProxyBuilder.http(Url(value)) }
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

    suspend fun sendMessageWithButton(
        chatId: Long,
        text: String,
        buttonText: String,
        callbackData: String,
    ): Long {
        return sendMessageWithButtons(
            chatId = chatId,
            text = text,
            buttons = listOf(
                TelegramInlineButton(
                    text = buttonText,
                    callbackData = callbackData,
                ),
            ),
        )
    }

    suspend fun sendMessageWithButtons(
        chatId: Long,
        text: String,
        buttons: List<TelegramInlineButton>,
    ): Long {
        require(buttons.isNotEmpty()) {
            "At least one Telegram button is required"
        }

        val replyMarkup = createReplyMarkup(buttons)
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
            .ifEmpty { listOf("(пустое сообщение)") }

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
                        """["callback_query"]""",
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

            val callbackQuery = update["callback_query"]
                ?.jsonObject
                ?: continue

            val callbackQueryId = callbackQuery["id"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: continue

            val fromUserId = callbackQuery["from"]
                ?.jsonObject
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
            )
        }

        return TelegramUpdateBatch(
            nextOffset = highestUpdateId?.plus(1),
            callbackQueries = callbackQueries,
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
    ): JsonObject {
        buttons.forEach { button ->
            val callbackDataSize = button.callbackData
                .toByteArray(Charsets.UTF_8)
                .size

            require(callbackDataSize in 1..64) {
                "Telegram callback data must contain 1-64 bytes"
            }
        }

        return buildJsonObject {
            put(
                "inline_keyboard",
                buildJsonArray {
                    add(
                        buildJsonArray {
                            buttons.forEach { button ->
                                add(
                                    buildJsonObject {
                                        put("text", button.text)
                                        put(
                                            "callback_data",
                                            button.callbackData,
                                        )
                                    },
                                )
                            }
                        },
                    )
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
            val description = root["description"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: "Unknown Telegram API error"

            val retryAfter = root["parameters"]
                ?.jsonObject
                ?.get("retry_after")
                ?.jsonPrimitive
                ?.intOrNull

            throw TelegramApiException(
                description = description,
                retryAfterSeconds = retryAfter,
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

        fun retryDelayMillis(attempt: Int): Long {
            return when (attempt) {
                1 -> 1_000L
                2 -> 2_500L
                else -> 5_000L
            }
        }
    }
}
