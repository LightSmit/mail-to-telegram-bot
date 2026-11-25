package io.github.lightsmit.api

import io.github.lightsmit.billing.InvoiceLinks
import io.github.lightsmit.billing.Offer
import io.github.lightsmit.billing.Plan
import io.github.lightsmit.billing.ReferralService
import io.github.lightsmit.billing.SubscriptionService
import io.github.lightsmit.billing.SubscriptionSource
import io.github.lightsmit.config.MailFilters
import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.service.AddMailAccountResult
import io.github.lightsmit.service.AdminPanelService
import io.github.lightsmit.service.AttachmentShareService
import io.github.lightsmit.service.MailAccountService
import io.github.lightsmit.mail.MailFolderRole
import io.github.lightsmit.mail.MailListPage
import io.github.lightsmit.mail.MailMessageView
import io.github.lightsmit.i18n.Lang
import io.github.lightsmit.i18n.Texts
import io.github.lightsmit.service.BotHealth
import io.github.lightsmit.service.BrowseFailure
import io.github.lightsmit.service.BrowseResult
import io.github.lightsmit.service.FunnelService
import io.github.lightsmit.service.FunnelStep
import io.github.lightsmit.service.GroupLinkService
import io.github.lightsmit.service.InboundMailService
import io.github.lightsmit.service.InboundResult
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
    // Ящик без пароля: письма пересылаются на этот адрес; null - обычный ящик с паролем
    val forwardAddress: String? = null,
    // На этот адрес уже пришло хотя бы одно письмо
    val forwardReceived: Boolean = false,
)

@Serializable
data class AddForwardRequestDto(
    val name: String = "",
    val email: String,
)

// Режим «Пересылка без пароля»: включен ли на сервере
@Serializable
data class ForwardingDto(
    val enabled: Boolean,
    val domain: String? = null,
)

@Serializable
data class FiltersDto(
    val paused: Boolean = false,
    val skipBulk: Boolean = false,
    val blockedSenders: List<String> = emptyList(),
    val blockedSubjectWords: List<String> = emptyList(),
    val allowedSenders: List<String> = emptyList(),
    // Пауза до этого момента (мс); null - бессрочно
    val pausedUntil: Long? = null,
    // Письма ящика без звука
    val silent: Boolean = false,
)

// Закончившаяся пауза на время показывается как выключенная
private fun MailFilters.toDto(): FiltersDto {
    val active = isPausedAt()
    return FiltersDto(active, skipBulk, blockedSenders, blockedSubjectWords, allowedSenders, pausedUntil.takeIf { active }, silent)
}

private fun FiltersDto.toModel() = MailFilters(paused, skipBulk, blockedSenders, blockedSubjectWords, allowedSenders, pausedUntil, silent)

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
    val starred: Boolean = false,
)

@Serializable
data class MailListPageDto(
    val uidValidity: Long,
    val total: Int,
    val items: List<MailListItemDto>,
    val hasMore: Boolean,
    // Бесплатный тариф: показаны только последние письма, остальные - в Pro
    val limited: Boolean = false,
    // Поиск остановился по времени (почта отвечает медленно)
    val partial: Boolean = false,
    // Поиск: продолжить с этого курсора; сколько писем просмотрено и сколько всего в папке
    val nextCursor: Int? = null,
    val scanned: Int = 0,
    val folderSize: Int = 0,
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
    val starred: Boolean = false,
    val html: String? = null,
)

@Serializable
data class MailFileDto(
    val index: Int,
    val name: String,
    val type: String,
    val size: Long,
    // Ссылка на скачивание без авторизации, живет 15 минут
    val url: String,
)

@Serializable
data class MailFilesDto(
    val items: List<MailFileDto>,
    // Вложения, которые бот не скачал (слишком большие и т.п.)
    val skipped: List<String> = emptyList(),
)

private fun MailListPage.toDto() = MailListPageDto(
    uidValidity = uidValidity,
    total = total,
    hasMore = hasMore,
    partial = partial,
    nextCursor = nextCursor,
    scanned = scanned,
    folderSize = folderSize,
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
            starred = item.starred,
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
    starred = starred,
    html = html,
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
    // Пробный Pro можно включить кнопкой (TRIAL_MODE=button) и на сколько дней
    val trialAvailable: Boolean = false,
    val trialDays: Int = 0,
    // Автопродление выключено, срок дорабатывает; его можно включить обратно
    val renewalCancelled: Boolean = false,
)

@Serializable
data class AdminGrantDto(
    val plan: String,
    // Срок как в /grant: 7d, 1m, 3m, 1y, 45d, forever
    val term: String,
)

@Serializable
data class AdminBroadcastDto(
    val text: String,
    // all, mailbox, nobox, paid, trial, free
    val audience: String = "all",
    // true - только себе, посмотреть сообщение
    val test: Boolean = false,
)

@Serializable
data class AdminRevokeDto(
    val refund: Boolean = false,
)

@Serializable
data class AutoRenewDto(
    val enabled: Boolean,
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
data class FunnelEventDto(
    val step: String,
    val detail: String? = null,
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
    // Воронка новых пользователей (null - выключена)
    private val funnel: FunnelService? = null,
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
    // Прием пересланных писем из Cloudflare Email Worker (null - режим выключен)
    private val inbound: InboundMailService? = null,
    private val inboundSecret: String? = null,
    // Тестовое сообщение «доставка работает» туда, куда приходят письма ящика
    private val sendTest: (suspend (MailAccountConfig) -> Boolean)? = null,
    // Админ-панель в приложении (только для админов из .env)
    private val adminPanel: AdminPanelService? = null,
    // «Поделиться» вложением через Telegram
    private val attachmentShare: AttachmentShareService? = null,
) {
    // Подписанные ссылки на вложения писем
    private val fileTokens = FileTokens()

    // Имя файла в заголовке: ASCII-замена и точное имя в UTF-8 (RFC 6266)
    private fun contentDisposition(name: String, inline: Boolean = false): String {
        val ascii = name.map { char -> if (char.code in 32..126 && char != '"' && char != '\\') char else '_' }.joinToString("")
        val encoded = java.net.URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
        return (if (inline) "inline" else "attachment") + "; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    // Ключ для проброса Telegram User ID между перехватчиком и роутом
    private val UserIdKey = AttributeKey<Long>("UserId")
    // Язык интерфейса пользователя (из Telegram): на нем отвечаем ошибками
    private val LangKey = AttributeKey<Lang>("Lang")

    private val ApplicationCall.texts: Texts get() = attributes[LangKey].texts

    // Тариф пользователя запроса; без тарифов - все возможности
    private suspend fun planOf(userId: Long): Plan = subscriptions?.plan(userId) ?: Plan.BUSINESS

    // Тариф, предложения для покупки и пробный период: для /plan и после включения пробного периода
    private suspend fun planDto(userId: Long): PlanDto {
        val service = subscriptions
            ?: return PlanDto(billingEnabled = false, plan = Plan.BUSINESS.name, maxMailboxes = Plan.BUSINESS.maxMailboxes)
        val status = service.status(userId)
        val offers = if (service.enabled) {
            service.config.offersOnSale.filter { offer -> service.canBuy(userId, offer) }
        } else {
            emptyList()
        }
        return PlanDto(
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
            trialAvailable = status.trialAvailable,
            trialDays = if (status.trialAvailable) service.config.trialDays else 0,
            renewalCancelled = status.renewalCancelled,
        )
    }

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
                // Некоторые мониторинги (UptimeRobot) проверяют адрес запросом HEAD: тот же ответ без тела
                head("/health") {
                    call.respond(if (BotHealth.healthy()) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
                }

                route("/api") {

                    // Перехватчик: Защита всех эндпоинтов /api
                    intercept(ApplicationCallPipeline.Call) {
                        // Письма из Cloudflare: своя проверка по секрету, без данных Telegram
                        if (call.request.path() == INBOUND_PATH) return@intercept
                        // Скачивание вложения: право на файл - в подписанной ссылке (ее открывает сам Telegram)
                        if (call.request.path().startsWith(FILES_PATH)) return@intercept

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
                        funnel?.onAppOpen(userId)
                        call.attributes.put(
                            LangKey,
                            runCatching { resolveLanguage(userId, user.second) }.getOrElse { Lang.fromCode(user.second) },
                        )
                    }

                    // Пересланное письмо от Cloudflare Email Worker: тело - письмо целиком (MIME)
                    post("/inbound") {
                        val service = inbound
                        val secret = inboundSecret
                        if (service == null || secret.isNullOrEmpty()) {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not found"))
                            return@post
                        }
                        val given = call.request.headers["X-Inbound-Secret"].orEmpty()
                        if (!java.security.MessageDigest.isEqual(given.toByteArray(), secret.toByteArray())) {
                            call.application.environment.log.warn("Inbound email rejected: wrong secret")
                            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                            return@post
                        }
                        val declared = call.request.contentLength()
                        if (declared != null && declared > InboundMailService.MAX_MESSAGE_BYTES) {
                            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Too large"))
                            return@post
                        }
                        val recipient = call.request.headers["X-Inbound-To"].orEmpty()
                        val raw = call.receive<ByteArray>()
                        val result = service.receive(recipient, raw)
                        when (result) {
                            InboundResult.ACCEPTED, InboundResult.DUPLICATE, InboundResult.FILTERED ->
                                call.respond(HttpStatusCode.Accepted, mapOf("status" to result.name.lowercase()))
                            InboundResult.UNKNOWN_ADDRESS ->
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown address"))
                            InboundResult.TOO_LARGE ->
                                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Too large"))
                            // Битое письмо: повтор не поможет, принимаем и не показываем
                            InboundResult.BAD_MESSAGE ->
                                call.respond(HttpStatusCode.Accepted, mapOf("status" to "unreadable"))
                        }
                    }

                    // Режим «Пересылка без пароля»: показывать ли его в приложении
                    get("/forwarding") {
                        val service = inbound
                        call.respond(ForwardingDto(enabled = service != null && accountService.forwardingEnabled, domain = service?.domain))
                    }

                    // Добавить ящик без пароля: бот выдает адрес для пересылки
                    post("/accounts/forward") {
                        val ownerId = call.attributes[UserIdKey]
                        val request = call.receive<AddForwardRequestDto>()
                        funnel?.record(ownerId, FunnelStep.SUBMIT)

                        when (val result = accountService.addForward(ownerId, request.email, request.name, call.attributes[LangKey])) {
                            is AddMailAccountResult.Added -> {
                                val account = result.account
                                funnel?.record(ownerId, FunnelStep.CONNECTED, "forward")
                                call.respond(
                                    HttpStatusCode.Created,
                                    AccountResponseDto(
                                        id = account.id,
                                        name = account.name,
                                        email = account.username,
                                        host = account.host,
                                        status = "OK",
                                        forwardAddress = accountService.forwardAddress(account),
                                    ),
                                )
                            }
                            is AddMailAccountResult.Rejected -> {
                                funnel?.record(ownerId, FunnelStep.CONNECT_ERROR, "forward_" + result.reason)
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
                            }
                            is AddMailAccountResult.LimitReached -> {
                                funnel?.record(ownerId, FunnelStep.CONNECT_ERROR, AddMailAccountResult.REASON_LIMIT)
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
                                forwardAddress = accountService.forwardAddress(account),
                                forwardReceived = accountService.forwardReceived(account),
                            )
                        }
                        call.respond(response)
                    }

                    // Добавить ящик: проверяем адрес сервера и вход, потом сохраняем и начинаем следить
                    // Шаги воронки, которые видит только приложение: какую почту выбрал человек
                    post("/funnel") {
                        val userId = call.attributes[UserIdKey]
                        val request = call.receive<FunnelEventDto>()
                        if (request.step == FunnelStep.PROVIDER.key) {
                            val provider = request.detail?.lowercase()?.filter { char -> char.isLetterOrDigit() || char == '-' }?.take(32)
                            funnel?.record(userId, FunnelStep.PROVIDER, provider?.ifEmpty { null })
                        }
                        call.respond(HttpStatusCode.NoContent)
                    }

                    post("/accounts") {
                        val ownerId = call.attributes[UserIdKey]
                        val request = call.receive<AddAccountRequestDto>()
                        funnel?.record(ownerId, FunnelStep.SUBMIT)

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
                                funnel?.record(ownerId, FunnelStep.CONNECTED)
                                call.respond(
                                    HttpStatusCode.Created,
                                    AccountResponseDto(id = account.id, name = account.name, email = account.username, host = account.host),
                                )
                            }

                            is AddMailAccountResult.Rejected -> {
                                funnel?.record(ownerId, FunnelStep.CONNECT_ERROR, result.reason)
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
                            }

                            is AddMailAccountResult.LimitReached -> {
                                funnel?.record(ownerId, FunnelStep.CONNECT_ERROR, AddMailAccountResult.REASON_LIMIT)
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
                        val starredOnly = call.request.queryParameters["starred"] == "true"
                        // Продолжение поиска: cursor из прошлого ответа (nextCursor)
                        val cursor = call.request.queryParameters["cursor"]?.toIntOrNull()?.takeIf { value -> value >= 1 }

                        // Free: только последние FREE_BROWSE_LIMIT входящих, без поиска и фильтра "непрочитанные"
                        val limited = !planOf(ownerId).hasProFeatures
                        if (limited) {
                            if (role != MailFolderRole.INBOX || !search.isNullOrBlank() || unreadOnly || starredOnly || offset >= FREE_BROWSE_LIMIT) {
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
                            starredOnly = starredOnly,
                            cursor = cursor,
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

                    // Вернуть письму статус «непрочитано» (только входящие)
                    post("/accounts/{id}/messages/{uid}/unread") {
                        val browser = mailBrowser ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val uid = call.parameters["uid"]?.toLongOrNull()
                        val uidValidity = call.request.queryParameters["v"]?.toLongOrNull()
                        if (accountId == null || uid == null || uidValidity == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            return@post
                        }

                        val result = browser.markRead(call.attributes[UserIdKey], accountId, uidValidity, uid, seen = false)
                        if (result is BrowseResult.Ok) call.respond(mapOf("status" to "unread")) else respondBrowseError(call, result)
                    }

                    // Звездочка «важное»: POST - поставить, DELETE - снять. ?folder=inbox|sent&v=UIDVALIDITY
                    route("/accounts/{id}/messages/{uid}/star") {
                        suspend fun ApplicationCall.star(value: Boolean) {
                            val browser = mailBrowser ?: return respond(HttpStatusCode.NotFound, mapOf("error" to texts.unavailable))
                            val accountId = parameters["id"]?.toLongOrNull()
                            val uid = parameters["uid"]?.toLongOrNull()
                            val uidValidity = request.queryParameters["v"]?.toLongOrNull()
                            val role = MailFolderRole.parse(request.queryParameters["folder"])
                            if (accountId == null || uid == null || uidValidity == null || role == null) {
                                return respond(HttpStatusCode.BadRequest, mapOf("error" to texts.badRequest))
                            }
                            val result = browser.star(attributes[UserIdKey], accountId, role, uidValidity, uid, value)
                            if (result is BrowseResult.Ok) respond(mapOf("status" to if (value) "starred" else "unstarred")) else respondBrowseError(this, result)
                        }
                        post { call.star(true) }
                        delete { call.star(false) }
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

                    // Вложения входящего письма для скачивания: список со ссылками на 15 минут
                    get("/accounts/{id}/messages/{uid}/files") {
                        val browser = mailBrowser ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val uid = call.parameters["uid"]?.toLongOrNull()
                        val uidValidity = call.request.queryParameters["v"]?.toLongOrNull()
                        if (accountId == null || uid == null || uidValidity == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            return@get
                        }

                        val ownerId = call.attributes[UserIdKey]
                        when (val result = browser.files(ownerId, accountId, uidValidity, uid)) {
                            is BrowseResult.Ok -> call.respond(
                                MailFilesDto(
                                    items = result.value.attachments.mapIndexed { index, file ->
                                        MailFileDto(
                                            index = index,
                                            name = file.fileName,
                                            type = file.contentType,
                                            size = file.sizeBytes,
                                            url = "$FILES_PATH/" + fileTokens.issue(ownerId, accountId, uidValidity, uid, index),
                                        )
                                    },
                                    skipped = result.value.skippedAttachments.map { file -> file.fileName },
                                ),
                            )
                            else -> respondBrowseError(call, result)
                        }
                    }

                    // Подготовить вложение для «Поделиться» в Telegram: ответ - id для WebApp.shareMessage
                    post("/accounts/{id}/messages/{uid}/files/{index}/share") {
                        val browser = mailBrowser ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                        val share = attachmentShare ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                        val uid = call.parameters["uid"]?.toLongOrNull()
                        val index = call.parameters["index"]?.toIntOrNull()
                        val uidValidity = call.request.queryParameters["v"]?.toLongOrNull()
                        if (accountId == null || uid == null || index == null || uidValidity == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            return@post
                        }

                        val ownerId = call.attributes[UserIdKey]
                        val result = browser.files(ownerId, accountId, uidValidity, uid)
                        if (result !is BrowseResult.Ok) {
                            respondBrowseError(call, result)
                            return@post
                        }
                        val file = result.value.attachments.getOrNull(index)
                            ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.badRequest))
                        try {
                            val prepared = share.prepare(ownerId, "$accountId:$uidValidity:$uid:$index", file)
                            call.respond(mapOf("id" to prepared))
                        } catch (exception: kotlinx.coroutines.CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            call.application.environment.log.warn("Share preparation failed: {}", exception.javaClass.simpleName)
                            call.respond(HttpStatusCode.BadGateway, mapOf("error" to call.texts.browseFailed))
                        }
                    }

                    // Сам файл вложения по подписанной ссылке (без данных Telegram: ее открывает Telegram)
                    get("$FILES_ROUTE/{token}") {
                        val browser = mailBrowser ?: return@get call.respond(HttpStatusCode.NotFound)
                        val ticket = call.parameters["token"]?.let(fileTokens::verify)
                            ?: return@get call.respond(HttpStatusCode.Forbidden, "Link expired")
                        val result = browser.files(ticket.ownerId, ticket.accountId, ticket.uidValidity, ticket.uid)
                        val file = (result as? BrowseResult.Ok)?.value?.attachments?.getOrNull(ticket.index)
                        if (file == null || !java.nio.file.Files.isRegularFile(file.tempFile)) {
                            call.respond(HttpStatusCode.NotFound, "File not found")
                            return@get
                        }
                        // Активное содержимое (HTML, SVG, скрипты) с нашего домена не открываем: только как файл
                        val declared = file.contentType.substringBefore(';').trim().lowercase()
                        val active = listOf("html", "xml", "svg", "javascript", "ecmascript").any(declared::contains)
                        val type = if (active) {
                            ContentType.Application.OctetStream
                        } else {
                            runCatching { ContentType.parse(declared) }.getOrDefault(ContentType.Application.OctetStream)
                        }
                        // ?inline=1 - просмотр в приложении: только картинки и PDF
                        val pdf = declared == "application/pdf"
                        val inline = call.request.queryParameters["inline"] == "1" &&
                            !active && (declared.startsWith("image/") || pdf)
                        call.response.header(HttpHeaders.ContentDisposition, contentDisposition(file.fileName, inline))
                        call.response.header(HttpHeaders.CacheControl, "private, no-store")
                        call.response.header("X-Content-Type-Options", "nosniff")
                        // Встроенному просмотрщику PDF песочница мешает; остальное - без активного содержимого
                        if (!(inline && pdf)) call.response.header("Content-Security-Policy", "default-src 'none'; img-src 'self'; sandbox")
                        call.respond(io.ktor.server.http.content.LocalFileContent(file.tempFile.toFile(), type))
                    }

                    // Админ-панель: только админы из .env, остальным 403
                    route("/admin") {
                        suspend fun ApplicationCall.panel(): AdminPanelService? {
                            val panel = adminPanel
                            if (panel == null || !panel.isAdmin(attributes[UserIdKey])) {
                                respond(HttpStatusCode.Forbidden, mapOf("error" to "Только для администратора"))
                                return null
                            }
                            return panel
                        }

                        suspend fun ApplicationCall.targetId(): Long? =
                            parameters["id"]?.toLongOrNull()?.takeIf { value -> value > 0 }
                                ?: run {
                                    respond(HttpStatusCode.BadRequest, mapOf("error" to texts.badRequest))
                                    null
                                }

                        get("/overview") {
                            val panel = call.panel() ?: return@get
                            call.respond(panel.overview())
                        }

                        // ?q=поиск&filter=all|paid|free|nobox|errors&limit=100
                        get("/users") {
                            val panel = call.panel() ?: return@get
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                            call.respond(panel.users(call.request.queryParameters["q"], call.request.queryParameters["filter"], limit))
                        }

                        get("/users/{id}") {
                            val panel = call.panel() ?: return@get
                            val id = call.targetId() ?: return@get
                            val card = panel.card(id)
                                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Пользователь не найден"))
                            call.respond(card)
                        }

                        post("/users/{id}/grant") {
                            val panel = call.panel() ?: return@post
                            val id = call.targetId() ?: return@post
                            val body = runCatching { call.receive<AdminGrantDto>() }.getOrNull()
                                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            call.respond(panel.grant(id, body.plan, body.term))
                        }

                        post("/users/{id}/revoke") {
                            val panel = call.panel() ?: return@post
                            val id = call.targetId() ?: return@post
                            val body = runCatching { call.receive<AdminRevokeDto>() }.getOrNull() ?: AdminRevokeDto()
                            call.respond(panel.revoke(id, body.refund))
                        }

                        post("/users/{id}/reset-trial") {
                            val panel = call.panel() ?: return@post
                            val id = call.targetId() ?: return@post
                            call.respond(panel.resetTrial(id))
                        }

                        delete("/users/{id}") {
                            val panel = call.panel() ?: return@delete
                            val id = call.targetId() ?: return@delete
                            call.respond(panel.deleteData(id))
                        }

                        // ?days=30&source=habr
                        get("/funnel") {
                            val panel = call.panel() ?: return@get
                            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
                            call.respond(panel.funnel(days, call.request.queryParameters["source"]))
                        }

                        get("/system") {
                            val panel = call.panel() ?: return@get
                            call.respond(panel.system())
                        }

                        post("/backup") {
                            val panel = call.panel() ?: return@post
                            call.respond(panel.backup())
                        }

                        get("/money") {
                            val panel = call.panel() ?: return@get
                            call.respond(panel.money())
                        }

                        // {"plan": "PRO", "term": "1m"}
                        post("/invite") {
                            val panel = call.panel() ?: return@post
                            val body = runCatching { call.receive<AdminGrantDto>() }.getOrNull()
                                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            call.respond(panel.invite(call.attributes[UserIdKey], body.plan, body.term))
                        }

                        get("/broadcast") {
                            val panel = call.panel() ?: return@get
                            call.respond(panel.audiences())
                        }

                        post("/broadcast") {
                            val panel = call.panel() ?: return@post
                            val body = runCatching { call.receive<AdminBroadcastDto>() }.getOrNull()
                                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                            call.respond(panel.broadcast(call.attributes[UserIdKey], body.text, body.audience, body.test))
                        }
                    }

                    // Проверка доставки: бот присылает тестовое сообщение туда, куда приходят письма ящика
                    post("/accounts/{id}/test") {
                        val send = sendTest ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                        val accountId = call.parameters["id"]?.toLongOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                        val account = accountService.find(call.attributes[UserIdKey], accountId)
                            ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.mailboxNotFound))
                        if (send(account)) {
                            call.respond(mapOf("status" to "sent"))
                        } else {
                            call.respond(HttpStatusCode.BadGateway, mapOf("error" to call.texts.deliveryTestFailed))
                        }
                    }

                    // Тариф пользователя и что можно купить
                    get("/plan") {
                        call.respond(planDto(call.attributes[UserIdKey]))
                    }

                    // Включить пробный Pro кнопкой в приложении (один раз на человека)
                    // Автопродление ежемесячной подписки: {"enabled": false} - выключить, true - вернуть
                    put("/plan/autorenew") {
                        val userId = call.attributes[UserIdKey]
                        val service = subscriptions
                            ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to call.texts.unavailable))
                        val body = runCatching { call.receive<AutoRenewDto>() }.getOrNull()
                            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to call.texts.badRequest))
                        when (service.setAutoRenew(userId, body.enabled)) {
                            SubscriptionService.AutoRenewResult.OK -> call.respond(planDto(userId))
                            SubscriptionService.AutoRenewResult.NOT_SUBSCRIPTION ->
                                call.respond(HttpStatusCode.Conflict, mapOf("error" to call.texts.billing.autoRenewUnavailable))
                            SubscriptionService.AutoRenewResult.FAILED ->
                                call.respond(HttpStatusCode.BadGateway, mapOf("error" to call.texts.billing.autoRenewFailed))
                        }
                    }

                    post("/plan/trial") {
                        val userId = call.attributes[UserIdKey]
                        val service = subscriptions
                        if (service == null || service.startTrial(userId) == null) {
                            call.respond(HttpStatusCode.Conflict, mapOf("error" to call.texts.billing.trialUnavailable))
                            return@post
                        }
                        funnel?.record(userId, FunnelStep.TRIAL)
                        call.respond(planDto(userId))
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
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to browseFailureText(call.texts, result.reason)))
            }
        }
    }

    private fun browseFailureText(texts: Texts, reason: BrowseFailure): String = when (reason) {
        BrowseFailure.TIMEOUT -> texts.browseTimeout
        BrowseFailure.AUTH -> texts.browseAuth
        BrowseFailure.NO_SENT_FOLDER -> texts.browseNoSent
        BrowseFailure.FORWARD_MODE -> texts.forwardNoBrowse
        BrowseFailure.OTHER -> texts.browseFailed
    }

    private companion object {
        const val INBOUND_PATH = "/api/inbound"
        const val FILES_ROUTE = "/files"
        const val FILES_PATH = "/api$FILES_ROUTE"
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