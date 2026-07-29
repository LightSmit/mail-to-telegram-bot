package io.github.lightsmit.telegram

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import io.github.lightsmit.mail.EmailAttachment
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.streams.asInput
import kotlinx.io.buffered

class TelegramClient(
    token: String,
) : AutoCloseable {

    private val baseUrl = "https://api.telegram.org/bot$token"

    private val httpClient = HttpClient(CIO) {
        expectSuccess = true
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun findLatestPrivateChatId(): Long? {
        val response = httpClient.get("$baseUrl/getUpdates") {
            url {
                parameters.append("limit", "100")
                parameters.append("timeout", "0")
            }
        }

        val root = parseSuccessfulResponse(response.bodyAsText())
        val updates = root["result"]?.jsonArray ?: return null

        return updates
            .asReversed()
            .firstNotNullOfOrNull { update ->
                val message = update.jsonObject["message"]?.jsonObject
                    ?: return@firstNotNullOfOrNull null

                val chat = message["chat"]?.jsonObject
                    ?: return@firstNotNullOfOrNull null

                val chatType = chat["type"]
                    ?.jsonPrimitive
                    ?.contentOrNull

                if (chatType != "private") {
                    return@firstNotNullOfOrNull null
                }

                chat["id"]
                    ?.jsonPrimitive
                    ?.longOrNull
            }
    }

    suspend fun sendMessage(
        chatId: Long,
        text: String,
    ) {
        val response = httpClient.submitForm(
            url = "$baseUrl/sendMessage",
            formParameters = Parameters.build {
                append("chat_id", chatId.toString())
                append("text", text)
            },
        )

        parseSuccessfulResponse(response.bodyAsText())
    }

    suspend fun sendLongMessage(
        chatId: Long,
        text: String,
    ) {
        splitText(text).forEach { part ->
            sendMessage(
                chatId = chatId,
                text = part,
            )
        }
    }

    suspend fun sendDocument(
        chatId: Long,
        attachment: EmailAttachment,
    ) {
        val safeFileName = attachment.fileName
            .replace('\\', '_')
            .replace('"', '_')
            .replace('\r', '_')
            .replace('\n', '_')

        val response = httpClient.post(
            "$baseUrl/sendDocument",
        ) {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "chat_id",
                            chatId.toString(),
                        )

                        append(
                            key = "document",

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

        parseSuccessfulResponse(
            response.bodyAsText(),
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

        if (normalizedText.isEmpty()) {
            return emptyList()
        }

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

    private fun parseSuccessfulResponse(body: String): JsonObject {
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

            error("Telegram API request failed: $description")
        }

        return root
    }

    override fun close() {
        httpClient.close()
    }
}