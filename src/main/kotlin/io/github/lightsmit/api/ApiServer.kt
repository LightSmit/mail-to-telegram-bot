package io.github.lightsmit.api

import io.github.lightsmit.config.MailFilters
import io.github.lightsmit.service.AddMailAccountResult
import io.github.lightsmit.service.MailAccountService
import io.github.lightsmit.service.NewMailAccount
import io.github.lightsmit.service.NotificationSchedule
import io.github.lightsmit.service.NotificationSettings
import io.github.lightsmit.service.NotificationSettingsService
import io.github.lightsmit.service.UpdatePasswordResult
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
data class AccountResponseDto(
    val id: Long,
    val name: String,
    val email: String,
    val host: String,
    // CONNECTING, OK или ERROR; problem - понятное описание ошибки
    val status: String = "CONNECTING",
    val problem: String? = null,
    val filters: FiltersDto = FiltersDto(),
)

@Serializable
data class FiltersDto(
    val paused: Boolean = false,
    val skipBulk: Boolean = false,
    val blockedSenders: List<String> = emptyList(),
    val blockedSubjectWords: List<String> = emptyList(),
    val allowedSenders: List<String> = emptyList(),
)

private fun MailFilters.toDto() = FiltersDto(paused, skipBulk, blockedSenders, blockedSubjectWords, allowedSenders)

private fun FiltersDto.toModel() = MailFilters(paused, skipBulk, blockedSenders, blockedSubjectWords, allowedSenders)

@Serializable
data class NotificationSettingsDto(
    val timezone: String = "Europe/Moscow",
    val quietEnabled: Boolean = false,
    val quietStart: String = "23:00",
    val quietEnd: String = "08:00",
    val digestHours: Int = 0,
)

private fun NotificationSettings.toDto() = NotificationSettingsDto(
    timezone = timezone,
    quietEnabled = quietEnabled,
    quietStart = NotificationSchedule.formatMinutes(quietStartMinutes),
    quietEnd = NotificationSchedule.formatMinutes(quietEndMinutes),
    digestHours = digestHours,
)

@Serializable
data class UpdatePasswordRequestDto(
    val appPassword: String,
)

@Serializable
data class AddAccountRequestDto(
    val name: String = "",
    val host: String,
    val port: Int = 993,
    val username: String,
    val appPassword: String
)

class ApiServer(
    private val port: Int = 8080,
    private val botToken: String,
    private val accountService: MailAccountService,
    // Вызывается для каждого авторизованного запроса: учет пользователей
    private val onUserSeen: suspend (Long) -> Unit = {},
    // Тихие часы и сводка; null - раздел в приложении не работает
    private val notificationSettings: NotificationSettingsService? = null,
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
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Put)
            }

            install(ContentNegotiation) {
                // encodeDefaults: пустые списки и false тоже попадают в ответ,
                // иначе приложение получает объект без полей и падает
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }

            // Лог каждого запроса: метод, путь, статус, время
            install(CallLogging)

            // Любое необработанное исключение логируем и отдаем как JSON 500
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
                        runCatching { onUserSeen(userId) }
                    }

                    // Ящики текущего пользователя
                    get("/accounts") {
                        val ownerId = call.attributes[UserIdKey]
                        val response = accountService.list(ownerId).map { account ->
                            val health = accountService.health(account.id)
                            AccountResponseDto(
                                id = account.id,
                                name = account.name,
                                email = account.username,
                                host = account.host,
                                status = health.status.name,
                                problem = health.problem,
                                filters = account.filters.toDto(),
                            )
                        }
                        call.respond(response)
                    }

                    // Добавить ящик: проверяем адрес сервера и вход, потом сохраняем и начинаем следить
                    post("/accounts") {
                        val ownerId = call.attributes[UserIdKey]
                        val request = call.receive<AddAccountRequestDto>()

                        val result = accountService.add(
                            ownerChatId = ownerId,
                            request = NewMailAccount(
                                name = request.name,
                                host = request.host,
                                port = request.port,
                                username = request.username,
                                password = request.appPassword,
                            ),
                        )

                        when (result) {
                            is AddMailAccountResult.Added -> {
                                val account = result.account
                                call.respond(
                                    HttpStatusCode.Created,
                                    AccountResponseDto(id = account.id, name = account.name, email = account.username, host = account.host),
                                )
                            }

                            is AddMailAccountResult.Rejected ->
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))

                            is AddMailAccountResult.LimitReached ->
                                call.respond(
                                    HttpStatusCode.Conflict,
                                    mapOf("error" to "Можно подключить не больше ${result.limit} ящиков"),
                                )
                        }
                    }

                    // Фильтры ящика: пауза, рассылки, отправители, слова в теме
                    put("/accounts/{id}/filters") {
                        val ownerId = call.attributes[UserIdKey]
                        val accountId = call.parameters["id"]?.toLongOrNull()

                        if (accountId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный ящик"))
                            return@put
                        }

                        val request = call.receive<FiltersDto>()
                        val updated = accountService.updateFilters(ownerId, accountId, request.toModel())

                        if (updated == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Ящик не найден"))
                        } else {
                            call.respond(HttpStatusCode.OK, updated.filters.toDto())
                        }
                    }

                    // Новый пароль приложения: проверяем вход и перезапускаем слежение
                    put("/accounts/{id}/password") {
                        val ownerId = call.attributes[UserIdKey]
                        val accountId = call.parameters["id"]?.toLongOrNull()

                        if (accountId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный ящик"))
                            return@put
                        }

                        val request = call.receive<UpdatePasswordRequestDto>()
                        when (val result = accountService.updatePassword(ownerId, accountId, request.appPassword)) {
                            is UpdatePasswordResult.Updated ->
                                call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
                            is UpdatePasswordResult.Rejected ->
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
                            UpdatePasswordResult.NotFound ->
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Ящик не найден"))
                        }
                    }

                    // Как присылать письма: тихие часы и сводка
                    get("/settings") {
                        val service = notificationSettings
                        if (service == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Настройки недоступны"))
                            return@get
                        }
                        call.respond(service.get(call.attributes[UserIdKey]).toDto())
                    }

                    put("/settings") {
                        val service = notificationSettings
                        if (service == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Настройки недоступны"))
                            return@put
                        }

                        val request = call.receive<NotificationSettingsDto>()
                        val quietStart = NotificationSchedule.parseMinutes(request.quietStart)
                        val quietEnd = NotificationSchedule.parseMinutes(request.quietEnd)
                        if (quietStart == null || quietEnd == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Время укажите в виде ЧЧ:ММ"))
                            return@put
                        }

                        val saved = service.save(
                            userId = call.attributes[UserIdKey],
                            requested = NotificationSettings(
                                timezone = request.timezone,
                                quietEnabled = request.quietEnabled,
                                quietStartMinutes = quietStart,
                                quietEndMinutes = quietEnd,
                                digestHours = request.digestHours,
                            ),
                        )
                        call.respond(saved.toDto())
                    }

                    // Удалить свой ящик
                    delete("/accounts/{id}") {
                        val ownerId = call.attributes[UserIdKey]
                        val accountId = call.parameters["id"]?.toLongOrNull()

                        if (accountId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный ящик"))
                            return@delete
                        }

                        if (accountService.delete(ownerId, accountId)) {
                            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Ящик не найден"))
                        }
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