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