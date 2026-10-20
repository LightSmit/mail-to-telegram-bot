package io.github.lightsmit.api

import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.storage.MailAccountRepository
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder

// --- DTO Модели для приема и отдачи JSON ---
@Serializable
data class AccountResponseDto(val name: String, val email: String, val host: String)

@Serializable
data class AddAccountRequestDto(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val appPassword: String
)

class ApiServer(
    private val port: Int = 8080,
    private val botToken: String,
    private val accountRepository: MailAccountRepository
) {
    // Ключ для проброса Telegram User ID между перехватчиком и роутом
    private val UserIdKey = AttributeKey<Long>("UserId")

    fun start() {
        embeddedServer(Netty, port = port) {
            install(CORS) {
                anyHost() // В проде заменишь на домен своего фронтенда
                allowHeader(HttpHeaders.ContentType)
                allowHeader("X-Telegram-Init-Data")
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
            }

            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }

            // Лог каждого запроса: метод, путь, статус, время
            install(CallLogging)

            // Любое необработанное исключение логируем и отдаём как JSON 500
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    call.application.environment.log.error(
                        "Unhandled error on ${call.request.local.method.value} ${call.request.local.uri}",
                        cause,
                    )
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal error"))
                }
            }

            routing {
                route("/api") {

                    // Перехватчик: Защита всех эндпоинтов /api
                    intercept(ApplicationCallPipeline.Call) {
                        val initData = call.request.headers["X-Telegram-Init-Data"].orEmpty()
                        val auth = TelegramAuthValidator.inspect(initData, botToken)

                        if (!auth.ok) {
                            // Без самих данных: initData содержит данные пользователя
                            call.application.environment.log.warn(
                                "API auth rejected for {} {}: reason={}, initData length={}, auth_date age={}s",
                                call.request.local.method.value,
                                call.request.local.uri,
                                auth.failure,
                                initData.length,
                                auth.authAgeSeconds,
                            )
                            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                            finish()
                            return@intercept
                        }

                        // Достаем user_id из initData
                        val userId = extractUserId(initData)
                        if (userId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user data"))
                            finish()
                            return@intercept
                        }

                        // Сохраняем user_id в контекст запроса
                        call.attributes.put(UserIdKey, userId)
                    }

                    // Получить список аккаунтов текущего пользователя
                    get("/accounts") {
                        val ownerId = call.attributes[UserIdKey]
                        val accounts = accountRepository.findAllByOwnerId(ownerId)

                        val response = accounts.map {
                            AccountResponseDto(name = it.name, email = it.username, host = it.host)
                        }
                        call.respond(response)
                    }

                    // Добавить новый аккаунт
                    post("/accounts") {
                        val ownerId = call.attributes[UserIdKey]
                        val request = call.receive<AddAccountRequestDto>()

                        val config = MailAccountConfig(
                            name = request.name,
                            host = request.host,
                            port = request.port,
                            username = request.username,
                            password = request.appPassword
                        )

                        accountRepository.save(ownerId, config)
                        call.respond(HttpStatusCode.Created, mapOf("status" to "success"))
                    }
                }
            }
        }.start(wait = false)
    }

    private fun extractUserId(initData: String): Long? {
        return try {
            val params = initData.split("&").associate { pair ->
                val parts = pair.split("=", limit = 2)
                parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
            val userJson = params["user"] ?: return null
            val userElement = Json.parseToJsonElement(userJson).jsonObject
            userElement["id"]?.jsonPrimitive?.longOrNull
        } catch (e: Exception) {
            null
        }
    }
}