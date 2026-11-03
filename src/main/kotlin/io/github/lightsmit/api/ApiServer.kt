package io.github.lightsmit.api

import io.github.lightsmit.billing.InvoiceLinks
import io.github.lightsmit.billing.Offer
import io.github.lightsmit.billing.Plan
import io.github.lightsmit.billing.ReferralService
import io.github.lightsmit.billing.SubscriptionService
import io.github.lightsmit.billing.SubscriptionSource
import io.github.lightsmit.config.MailFilters
import io.github.lightsmit.service.AddMailAccountResult
import io.github.lightsmit.service.MailAccountService
import io.github.lightsmit.mail.MailFolderRole
import io.github.lightsmit.mail.MailListPage
import io.github.lightsmit.mail.MailMessageView
import io.github.lightsmit.i18n.Lang
import io.github.lightsmit.i18n.Texts
import io.github.lightsmit.service.BotHealth
import io.github.lightsmit.service.BrowseFailure
import io.github.lightsmit.service.BrowseResult
import io.github.lightsmit.service.GroupLinkService
import io.github.lightsmit.service.MailBrowserService
import io.github.lightsmit.service.MailboxQuota
import io.github.lightsmit.service.NewMailAccount
import io.github.lightsmit.service.NotificationSchedule
import io.github.lightsmit.service.NotificationSettings
import io.github.lightsmit.service.NotificationSettingsService
import io.github.lightsmit.service.UpdatePasswordResult
import io.github.lightsmit.service.UserDataService
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
import kotlinx.serialization.json.contentOrNull
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
    // Ящик сверх лимита тарифа: письма из него не приходят
    val overLimit: Boolean = false,
    // Групповой чат, куда приходят письма ящика (Business); null - в личный чат
    val groupTitle: String? = null,
    val groupLinked: Boolean = false,
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
    // Бесплатный тариф: показаны только последние письма, остальные - в Pro
    val limited: Boolean = false,
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
data class LanguageDto(
    val language: String,
)

@Serializable
data class OfferDto(
    val id: String,
    val plan: String,
    // month или year
    val period: String,
    val stars: Int,
)

@Serializable
data class PlanDto(
    val billingEnabled: Boolean,
    val plan: String,
    // Время окончания в миллисекундах (UTC); null - бессрочно
    val expiresAt: Long? = null,
    val lifetime: Boolean = false,
    val recurring: Boolean = false,
    val trial: Boolean = false,
    val admin: Boolean = false,
    val maxMailboxes: Int,
    val offers: List<OfferDto> = emptyList(),
)

@Serializable
data class ReferralDto(
    val enabled: Boolean,
    val link: String? = null,
    val invited: Int = 0,
    val qualified: Int = 0,
    val goal: Int = 0,
    val towardsNext: Int = 0,
    val rewards: Int = 0,
    // Текст, который подставится при «Поделиться»
    val shareText: String = "",
)

@Serializable
data class InvoiceRequestDto(
    val offer: String,
)

@Serializable
data class InvoiceLinkDto(
    val link: String,
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
    private val onUserSeen: suspend (Long, String?) -> Unit = { _, _ -> },
    // Язык интерфейса: выбранный вручную или из Telegram
    private val resolveLanguage: suspend (Long, String?) -> Lang = { _, code -> Lang.fromCode(code) },
    private val setLanguage: suspend (Long, Lang) -> Unit = { _, _ -> },
    // Тихие часы и сводка; null - раздел в приложении не работает
    private val notificationSettings: NotificationSettingsService? = null,
    // Просмотр писем ящика в приложении
    private val mailBrowser: MailBrowserService? = null,
    // Удаление всех данных пользователя
    private val userData: UserDataService? = null,
    // Тарифы и оплата звездами; null - тарифов нет, все доступно
    private val subscriptions: SubscriptionService? = null,
    private val invoiceLinks: InvoiceLinks? = null,
    // Какие ящики сверх лимита тарифа
    private val quota: MailboxQuota? = null,
    // Пересылка писем ящика в групповой чат (Business)
    private val groupLinks: GroupLinkService? = null,
    // Имя бота для ссылки t.me/бот?startgroup=
    private val botUsername: suspend () -> String? = { null },
    // Приглашение друзей
    private val referrals: ReferralService? = null,
) {
    // Ключ для проброса Telegram User ID между перехватчиком и роутом
    private val UserIdKey = AttributeKey<Long>("UserId")
    // Язык интерфейса пользователя (из Telegram): на нем отвечаем ошибками
    private val LangKey = AttributeKey<Lang>("Lang")

    private val ApplicationCall.texts: Texts get() = attributes[LangKey].texts

    // Тариф пользователя запроса; без тарифов - все возможности
    private suspend fun planOf(userId: Long): Plan = subscriptions?.plan(userId) ?: Plan.BUSINESS

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
                // Для внешнего мониторинга (UptimeRobot и т.п.): 200 - бот работает, 503 - нет связи с Telegram.
                // Без авторизации и без данных пользователей
                get("/health") {
                    if (BotHealth.healthy()) {
                        call.respond(mapOf("status" to "ok"))
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "telegram unreachable"))
                    }
                }

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

                        // Достаем пользователя из initData: id и язык
                        val user = extractUser(initData)
                        val userId = user?.first
                        if (userId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user data"))
                            finish()
                            return@intercept
                        }

                        // Сохраняем user_id в контекст запроса
                        call.attributes.put(UserIdKey, userId)
                        runCatching { onUserSeen(userId, user.second) }
                        call.attributes.put(
                            LangKey,
                            runCatching { resolveLanguage(userId, user.second) }.getOrElse { Lang.fromCode(user.second) },
                        )
                    }

                    // Ящики текущего пользователя
                    get("/accounts") {
                        val ownerId = call.attributes[UserIdKey]
                        val overLimit = quota?.overLimitIds(ownerId).orEmpty()
                        val response = accountService.list(ownerId).map { account ->
                            val health = accountService.health(account.id)
                            AccountResponseDto(
                                id = account.id,
                                name = account.name,
                                email = account.username,
                                host = account.host,
                                status = health.status.name,
                                problem = health.problem?.text(call.texts),
                                filters = account.filters.toDto(),
                                overLimit = account.id in overLimit,
                                groupTitle = account.groupTitle,
                                groupLinked = account.groupChatId != null,
                            )
                        }
                        call.respond(response)
                    }

                    // Добавить ящик: проверяем адрес сервера и вход, потом сохраняем и начинаем следить
                    post("/accounts") {
                        val ownerId = call.attributes[UserIdKey]
                        val request = call.receive<AddAccountRequestDto>()

                        val result = accountService.add(
                            lang = call.attributes[LangKey],
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
                                    mapOf(
                                        "error" to call.texts.billing.limitReached(
                                            plan = planOf(ownerId),
                                            limit = result.limit,
                                            billingEnabled = subscriptions?.enabled == true,
                                        ),
                                    ),
                                )
                        }
                    }

                    // Фильтры ящика: пауза, рассылки, отправители, слова в теме
                    put("/accounts/{id}/filters") {
                        val ownerId = call.attributes[UserIdKey]
                        val accountId = call.parameters["id"]?.toLongOrNull()

                        if (accountId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.invalidMailbox))
                            return@put
                        }

                        val request = call.receive<FiltersDto>()

                        // На Free списки отправителей и слов менять нельзя (сохраненные остаются, но не действуют)
                        if (!planOf(ownerId).hasProFeatures) {
                            val current = accountService.find(ownerId, accountId)?.filters
                            if (current == null) {
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.mailboxNotFound))
                                return@put
                            }
                            val listsChanged = request.allowedSenders != current.allowedSenders ||
                                request.blockedSenders != current.blockedSenders ||
                                request.blockedSubjectWords != current.blockedSubjectWords
                            if (listsChanged) {
                                call.respond(HttpStatusCode.PaymentRequired, mapOf("error" to call.texts.billing.proFilters))
                                return@put
                            }
                        }

                        val updated = accountService.updateFilters(ownerId, accountId, request.toModel())

                        if (updated == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.mailboxNotFound))
                        } else {
                            call.respond(HttpStatusCode.OK, updated.filters.toDto())
                        }
                    }

                    // Ссылка, по которой Telegram предложит выбрать группу и добавить в нее бота
                    post("/accounts/{id}/group") {
                        val ownerId = call.attributes[UserIdKey]
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val links = groupLinks
                        if (accountId == null || links == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            return@post
                        }
                        if (!links.allowed(ownerId)) {
                            call.respond(HttpStatusCode.PaymentRequired, mapOf("error" to call.texts.billing.groupBusinessOnly))
                            return@post
                        }
                        val code = links.createCode(ownerId, accountId)
                        if (code == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.mailboxNotFound))
                            return@post
                        }
                        val username = botUsername()
                        if (username == null) {
                            call.respond(HttpStatusCode.BadGateway, mapOf("error" to call.texts.unavailable))
                            return@post
                        }
                        call.respond(InvoiceLinkDto("https://t.me/$username?startgroup=$code"))
                    }

                    // Отключить группу: письма снова приходят в личный чат
                    delete("/accounts/{id}/group") {
                        val ownerId = call.attributes[UserIdKey]
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val links = groupLinks
                        if (accountId == null || links == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            return@delete
                        }
                        if (links.unlink(ownerId, accountId) == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.mailboxNotFound))
                        } else {
                            call.respond(mapOf("status" to "unlinked"))
                        }
                    }

                    // Новый пароль приложения: проверяем вход и перезапускаем слежение
                    put("/accounts/{id}/password") {
                        val ownerId = call.attributes[UserIdKey]
                        val accountId = call.parameters["id"]?.toLongOrNull()

                        if (accountId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.invalidMailbox))
                            return@put
                        }

                        val request = call.receive<UpdatePasswordRequestDto>()
                        when (val result = accountService.updatePassword(ownerId, accountId, request.appPassword, call.attributes[LangKey])) {
                            is UpdatePasswordResult.Updated ->
                                call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
                            is UpdatePasswordResult.Rejected ->
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
                            UpdatePasswordResult.NotFound ->
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.mailboxNotFound))
                        }
                    }

                    // Как присылать письма: тихие часы и сводка
                    get("/settings") {
                        val service = notificationSettings
                        if (service == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                            return@get
                        }
                        call.respond(service.get(call.attributes[UserIdKey]).toDto())
                    }

                    put("/settings") {
                        val service = notificationSettings
                        if (service == null) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                            return@put
                        }

                        val request = call.receive<NotificationSettingsDto>()
                        val quietStart = NotificationSchedule.parseMinutes(request.quietStart)
                        val quietEnd = NotificationSchedule.parseMinutes(request.quietEnd)
                        if (quietStart == null || quietEnd == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.timeFormat))
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
                        val browser = mailBrowser ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val role = MailFolderRole.parse(call.request.queryParameters["folder"])
                        if (accountId == null || role == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            return@get
                        }

                        val ownerId = call.attributes[UserIdKey]
                        val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                        var limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
                        val search = call.request.queryParameters["q"]
                        val unreadOnly = call.request.queryParameters["unread"] == "true"

                        // Free: только последние FREE_BROWSE_LIMIT входящих, без поиска и фильтра "непрочитанные"
                        val limited = !planOf(ownerId).hasProFeatures
                        if (limited) {
                            if (role != MailFolderRole.INBOX || !search.isNullOrBlank() || unreadOnly || offset >= FREE_BROWSE_LIMIT) {
                                call.respond(HttpStatusCode.PaymentRequired, mapOf("error" to call.texts.billing.proBrowse))
                                return@get
                            }
                            limit = minOf(limit, FREE_BROWSE_LIMIT - offset)
                        }

                        val result = browser.list(
                            ownerId = ownerId,
                            accountId = accountId,
                            role = role,
                            offset = offset,
                            limit = limit,
                            search = search,
                            unreadOnly = unreadOnly,
                        )
                        if (result is BrowseResult.Ok) {
                            val page = result.value.toDto()
                            call.respond(
                                if (limited) {
                                    page.copy(hasMore = false, limited = page.hasMore || offset + page.items.size >= FREE_BROWSE_LIMIT)
                                } else {
                                    page
                                },
                            )
                        } else {
                            respondBrowseError(call, result)
                        }
                    }

                    // Одно письмо: ?folder=inbox|sent&v=UIDVALIDITY
                    get("/accounts/{id}/messages/{uid}") {
                        val browser = mailBrowser ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val uid = call.parameters["uid"]?.toLongOrNull()
                        val uidValidity = call.request.queryParameters["v"]?.toLongOrNull()
                        val role = MailFolderRole.parse(call.request.queryParameters["folder"])
                        if (accountId == null || uid == null || uidValidity == null || role == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            return@get
                        }

                        val result = browser.read(call.attributes[UserIdKey], accountId, role, uidValidity, uid)
                        if (result is BrowseResult.Ok) call.respond(result.value.toDto()) else respondBrowseError(call, result)
                    }

                    // Отметить прочитанным (только входящие)
                    post("/accounts/{id}/messages/{uid}/read") {
                        val browser = mailBrowser ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val uid = call.parameters["uid"]?.toLongOrNull()
                        val uidValidity = call.request.queryParameters["v"]?.toLongOrNull()
                        if (accountId == null || uid == null || uidValidity == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            return@post
                        }

                        val result = browser.markRead(call.attributes[UserIdKey], accountId, uidValidity, uid)
                        if (result is BrowseResult.Ok) call.respond(mapOf("status" to "read")) else respondBrowseError(call, result)
                    }

                    // Прислать вложения письма в чат с ботом (только входящие)
                    post("/accounts/{id}/messages/{uid}/attachments") {
                        val browser = mailBrowser ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val uid = call.parameters["uid"]?.toLongOrNull()
                        val uidValidity = call.request.queryParameters["v"]?.toLongOrNull()
                        if (accountId == null || uid == null || uidValidity == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            return@post
                        }

                        val result = browser.sendAttachments(call.attributes[UserIdKey], accountId, uidValidity, uid)
                        if (result is BrowseResult.Ok) call.respond(mapOf("status" to "sending")) else respondBrowseError(call, result)
                    }

                    // Тариф пользователя и что можно купить
                    get("/plan") {
                        val userId = call.attributes[UserIdKey]
                        val service = subscriptions
                        if (service == null) {
                            call.respond(PlanDto(billingEnabled = false, plan = Plan.BUSINESS.name, maxMailboxes = Plan.BUSINESS.maxMailboxes))
                            return@get
                        }
                        val status = service.status(userId)
                        val offers = if (service.enabled) {
                            service.config.offersOnSale.filter { offer -> service.canBuy(userId, offer) }
                        } else {
                            emptyList()
                        }
                        call.respond(
                            PlanDto(
                                billingEnabled = service.enabled,
                                plan = status.plan.name,
                                expiresAt = status.expiresAt?.toEpochMilli(),
                                lifetime = status.lifetime,
                                recurring = status.recurring,
                                trial = status.source == SubscriptionSource.TRIAL,
                                admin = status.admin,
                                maxMailboxes = status.plan.maxMailboxes,
                                offers = offers.map { offer ->
                                    OfferDto(
                                        id = offer.name,
                                        plan = offer.plan.name,
                                        period = if (offer.recurring) "month" else "year",
                                        stars = service.config.price(offer),
                                    )
                                },
                            ),
                        )
                    }

                    // Своя ссылка-приглашение и счетчик друзей
                    get("/referral") {
                        val userId = call.attributes[UserIdKey]
                        val service = referrals
                        val username = botUsername()
                        if (service == null || !service.enabled || username == null) {
                            call.respond(ReferralDto(enabled = false))
                            return@get
                        }
                        val stats = service.stats(userId)
                        call.respond(
                            ReferralDto(
                                enabled = true,
                                link = "https://t.me/$username?start=${service.code(userId)}",
                                invited = stats.invited,
                                qualified = stats.qualified,
                                goal = stats.goal,
                                towardsNext = stats.towardsNext,
                                rewards = stats.rewards,
                                shareText = call.texts.billing.referralShareText,
                            ),
                        )
                    }

                    // Ссылка на оплату: приложение открывает ее через Telegram.WebApp.openInvoice
                    post("/plan/invoice") {
                        val userId = call.attributes[UserIdKey]
                        val service = subscriptions
                        val links = invoiceLinks
                        val offer = Offer.parse(call.receive<InvoiceRequestDto>().offer)
                        if (service == null || links == null || offer == null || !service.config.onSale(offer)) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            return@post
                        }
                        if (!service.canBuy(userId, offer)) {
                            call.respond(HttpStatusCode.Conflict, mapOf("error" to call.texts.billing.checkoutRejected))
                            return@post
                        }
                        call.respond(InvoiceLinkDto(links.link(offer, call.attributes[LangKey])))
                    }

                    // Язык интерфейса: какой сейчас и смена вручную (кнопка RU / EN в приложении)
                    get("/language") {
                        call.respond(LanguageDto(call.attributes[LangKey].name.lowercase()))
                    }

                    put("/language") {
                        val request = call.receive<LanguageDto>()
                        val lang = when (request.language.lowercase()) {
                            "ru" -> Lang.RU
                            "en" -> Lang.EN
                            else -> null
                        }
                        if (lang == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            return@put
                        }
                        setLanguage(call.attributes[UserIdKey], lang)
                        call.respond(LanguageDto(lang.name.lowercase()))
                    }

                    // Удалить все свои данные: ящики, настройки, историю
                    delete("/me") {
                        val service = userData ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                        val deleted = service.deleteAll(call.attributes[UserIdKey])
                        call.respond(mapOf("mailboxes" to deleted))
                    }

                    // Удалить свой ящик
                    delete("/accounts/{id}") {
                        val ownerId = call.attributes[UserIdKey]
                        val accountId = call.parameters["id"]?.toLongOrNull()

                        if (accountId == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.invalidMailbox))
                            return@delete
                        }

                        if (accountService.delete(ownerId, accountId)) {
                            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.mailboxNotFound))
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
            BrowseResult.MailboxNotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.mailboxNotFound))
            BrowseResult.MessageNotFound -> call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to call.texts.emailNotFound),
            )
            is BrowseResult.Failed -> {
                val texts = call.texts
                val message = when (result.reason) {
                    BrowseFailure.TIMEOUT -> texts.browseTimeout
                    BrowseFailure.AUTH -> texts.browseAuth
                    BrowseFailure.NO_SENT_FOLDER -> texts.browseNoSent
                    BrowseFailure.OTHER -> texts.browseFailed
                }
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to message))
            }
        }
    }

    private companion object {
        // Сколько последних писем видно в приложении на бесплатном тарифе
        const val FREE_BROWSE_LIMIT = 20
    }

    // id пользователя и его language_code из уже проверенного initData
    private fun extractUser(initData: String): Pair<Long, String?>? {
        return try {
            val params = initData.split("&").associate { pair ->
                val parts = pair.split("=", limit = 2)
                parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
            val userJson = params["user"] ?: return null
            val userElement = Json.parseToJsonElement(userJson).jsonObject
            val id = userElement["id"]?.jsonPrimitive?.longOrNull ?: return null
            id to userElement["language_code"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }
}