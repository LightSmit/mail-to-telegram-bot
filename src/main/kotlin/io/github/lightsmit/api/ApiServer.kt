package io.github.lightsmit.api

import io.github.lightsmit.config.MailFilters
import io.github.lightsmit.service.AddMailAccountResult
import io.github.lightsmit.service.MailAccountService
import io.github.lightsmit.mail.MailFolderRole
import io.github.lightsmit.mail.MailListPage
import io.github.lightsmit.mail.MailMessageView
import io.github.lightsmit.service.BrowseResult
import io.github.lightsmit.service.MailBrowserService
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
data class MailListItemDto(
    val uid: Long,
    val fromName: String,
    val fromAddress: String? = null,
    val to: String,
    val subject: String,
    // Время в миллисекундах (UTC), форматирует приложение
    val date: Long? = null,
    val seen: Boolean,
    val hasAttachments: Boolean,
)

@Serializable
data class MailListPageDto(
    val uidValidity: Long,
    val total: Int,
    val items: List<MailListItemDto>,
    val hasMore: Boolean,
)

@Serializable
data class MailMessageDto(
    val uid: Long,
    val from: String,
    val to: String,
    val cc: String,
    val subject: String,
    val date: Long? = null,
    val body: String? = null,
    val attachments: List<String>,
    val seen: Boolean,
)

private fun MailListPage.toDto() = MailListPageDto(
    uidValidity = uidValidity,
    total = total,
    hasMore = hasMore,
    items = items.map { item ->
        MailListItemDto(
            uid = item.uid,
            fromName = item.fromName,
            fromAddress = item.fromAddress,
            to = item.to,
            subject = item.subject,
            date = item.date?.toEpochMilli(),
            seen = item.seen,
            hasAttachments = item.hasAttachments,
        )
    },
)

private fun MailMessageView.toDto() = MailMessageDto(
    uid = uid,
    from = from,
    to = to,
    cc = cc,
    subject = subject,
    date = date?.toEpochMilli(),
    body = body,
    attachments = attachments,
    seen = seen,
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
    // Просмотр писем ящика в приложении
    private val mailBrowser: MailBrowserService? = null,
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

                    // Список писем ящика: ?folder=inbox|sent&offset=0&limit=30&q=поиск&unread=true
                    get("/accounts/{id}/messages") {
                        val browser = mailBrowser ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Просмотр писем недоступен"))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val role = MailFolderRole.parse(call.request.queryParameters["folder"])
                        if (accountId == null || role == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный запрос"))
                            return@get
                        }

                        val result = browser.list(
                            ownerId = call.attributes[UserIdKey],
                            accountId = accountId,
                            role = role,
                            offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0,
                            limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30,
                            search = call.request.queryParameters["q"],
                            unreadOnly = call.request.queryParameters["unread"] == "true",
                        )
                        if (result is BrowseResult.Ok) call.respond(result.value.toDto()) else respondBrowseError(call, result)
                    }

                    // Одно письмо: ?folder=inbox|sent&v=UIDVALIDITY
                    get("/accounts/{id}/messages/{uid}") {
                        val browser = mailBrowser ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Просмотр писем недоступен"))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val uid = call.parameters["uid"]?.toLongOrNull()
                        val uidValidity = call.request.queryParameters["v"]?.toLongOrNull()
                        val role = MailFolderRole.parse(call.request.queryParameters["folder"])
                        if (accountId == null || uid == null || uidValidity == null || role == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный запрос"))
                            return@get
                        }

                        val result = browser.read(call.attributes[UserIdKey], accountId, role, uidValidity, uid)
                        if (result is BrowseResult.Ok) call.respond(result.value.toDto()) else respondBrowseError(call, result)
                    }

                    // Отметить прочитанным (только входящие)
                    post("/accounts/{id}/messages/{uid}/read") {
                        val browser = mailBrowser ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Просмотр писем недоступен"))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val uid = call.parameters["uid"]?.toLongOrNull()
                        val uidValidity = call.request.queryParameters["v"]?.toLongOrNull()
                        if (accountId == null || uid == null || uidValidity == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный запрос"))
                            return@post
                        }

                        val result = browser.markRead(call.attributes[UserIdKey], accountId, uidValidity, uid)
                        if (result is BrowseResult.Ok) call.respond(mapOf("status" to "read")) else respondBrowseError(call, result)
                    }

                    // Прислать вложения письма в чат с ботом (только входящие)
                    post("/accounts/{id}/messages/{uid}/attachments") {
                        val browser = mailBrowser ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Просмотр писем недоступен"))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val uid = call.parameters["uid"]?.toLongOrNull()
                        val uidValidity = call.request.queryParameters["v"]?.toLongOrNull()
                        if (accountId == null || uid == null || uidValidity == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Некорректный запрос"))
                            return@post
                        }

                        val result = browser.sendAttachments(call.attributes[UserIdKey], accountId, uidValidity, uid)
                        if (result is BrowseResult.Ok) call.respond(mapOf("status" to "sending")) else respondBrowseError(call, result)
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

    // Ошибки просмотра писем: всегда {"error": "понятный текст"}
    private suspend fun respondBrowseError(
        call: ApplicationCall,
        result: BrowseResult<*>,
    ) {
        when (result) {
            is BrowseResult.Ok -> call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal error"))
            BrowseResult.MailboxNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Ящик не найден"))
            BrowseResult.MessageNotFound -> call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "Письмо не найдено: его могли удалить или переместить"),
            )
            is BrowseResult.Failed -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to result.message))
        }
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