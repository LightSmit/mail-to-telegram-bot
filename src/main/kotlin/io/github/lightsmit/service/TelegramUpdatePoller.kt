package io.github.lightsmit.service

import io.github.lightsmit.billing.InvoiceLinks
import io.github.lightsmit.billing.ReferralService
import io.github.lightsmit.billing.SubscriptionService
import io.github.lightsmit.config.MailAccountConfig
import io.github.lightsmit.i18n.Lang
import io.github.lightsmit.i18n.LANGUAGE_PROMPT
import io.github.lightsmit.i18n.Texts
import io.github.lightsmit.i18n.botCommands
import io.github.lightsmit.telegram.TelegramInlineButton
import io.github.lightsmit.storage.BotUserRepository
import io.github.lightsmit.storage.TelegramUpdateStateRepository
import io.github.lightsmit.telegram.MailViewAction
import io.github.lightsmit.telegram.MailViewCallbackCodec
import io.github.lightsmit.telegram.TelegramApiException
import io.github.lightsmit.telegram.TelegramCallbackQueryUpdate
import io.github.lightsmit.telegram.TelegramChatMemberUpdate
import io.github.lightsmit.telegram.TelegramClient
import io.github.lightsmit.telegram.TelegramMessageUpdate
import io.github.lightsmit.telegram.TelegramPreCheckoutQuery
import io.github.lightsmit.telegram.TelegramSuccessfulPayment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class TelegramUpdatePoller(
    private val registry: MailAccountRegistry,
    private val pollingClient: TelegramClient,
    private val controlClient: TelegramClient,
    private val forwardingService: MailForwardingService,
    private val stateRepository: TelegramUpdateStateRepository,
    // Адрес Mini App для кнопки в приветствии; null - кнопку не показываем
    private val miniAppUrl: String? = null,
    // Для команды /mailboxes: список ящиков пользователя и их статус
    private val accountService: MailAccountService? = null,
    // Учет пользователей бота (статистика, рассылки)
    private val botUsers: BotUserRepository? = null,
    // Команды администратора: /admin, /broadcast, /backup
    private val adminService: AdminService? = null,
    // Ответ на письмо из Telegram; null - кнопка "Ответить" не работает
    private val replyService: MailReplyService? = null,
    // Удаление всех данных пользователя по команде /delete_my_data
    private val userData: UserDataService? = null,
    // Тарифы и оплата звездами (/premium, приглашения); null - без тарифов
    private val subscriptions: SubscriptionService? = null,
    private val invoiceLinks: InvoiceLinks? = null,
    // Лимит ящиков по тарифу: для статуса в /mailboxes
    private val quota: MailboxQuota? = null,
    // Пересылка писем в групповые чаты (Business)
    private val groupLinks: GroupLinkService? = null,
    // Приглашение друзей (/referral, ссылки ?start=ref_)
    private val referrals: ReferralService? = null,
    // Имя бота для реферальной ссылки
    private val botUsername: suspend () -> String? = { null },
) {

    private val logger =
        LoggerFactory.getLogger(TelegramUpdatePoller::class.java)

    private val inProgressEmails =
        ConcurrentHashMap.newKeySet<String>()

    suspend fun run() = supervisorScope {
        var nextOffset = stateRepository.findNextOffset()

        while (currentCoroutineContext().isActive) {
            try {
                val batch = pollingClient.getCallbackQueryUpdates(
                    offset = nextOffset,
                    timeoutSeconds = LONG_POLL_TIMEOUT_SECONDS,
                )

                batch.nextOffset?.let { offset ->
                    stateRepository.saveNextOffset(offset)
                    nextOffset = offset
                }

                for (callbackQuery in batch.callbackQueries) {
                    launch {
                        handleCallbackQuery(callbackQuery)
                    }
                }

                for (message in batch.messages) {
                    launch {
                        handleMessage(message)
                    }
                }

                for (query in batch.preCheckoutQueries) {
                    launch {
                        handlePreCheckout(query)
                    }
                }

                for (member in batch.chatMemberUpdates) {
                    launch {
                        handleChatMember(member)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warn(
                    "Telegram update polling failed: {}",
                    exception.javaClass.simpleName,
                )
                delay(POLL_RETRY_DELAY_MILLIS)
            }
        }
    }

    private suspend fun handleCallbackQuery(
        update: TelegramCallbackQueryUpdate,
    ) {
        touchUser(update.fromUserId, update.languageCode)
        var t = textsFor(update.fromUserId, update.languageCode)

        // Кнопки работают в личном чате с ботом и в группе, к которой привязан ящик (Business)
        val chatId = update.chatId
        val inGroup = chatId != null && chatId != update.fromUserId
        if (chatId == null || (inGroup && chatId > 0)) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = t.accessDenied,
                showAlert = true,
            )
            return
        }

        if (!inGroup) {
            update.data?.takeIf { data -> data.startsWith(LANGUAGE_CALLBACK_PREFIX) }?.let { data ->
                chooseLanguage(update, data.removePrefix(LANGUAGE_CALLBACK_PREFIX))
                return
            }
        }

        val request = update.data
            ?.let(MailViewCallbackCodec::decode)

        if (request == null) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = t.badButton,
                showAlert = true,
            )
            return
        }

        // В личном чате ищем только среди ящиков нажавшего, в группе - среди ящиков этой группы:
        // чужие письма открыть нельзя
        val account = if (inGroup) {
            registry.findForGroupCallback(accountCode = request.accountCode, groupChatId = chatId)
        } else {
            registry.findForCallback(accountCode = request.accountCode, ownerChatId = update.fromUserId)
        }

        if (account == null) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = t.mailboxGone,
                showAlert = true,
            )
            return
        }

        // В группе карточки на языке владельца ящика
        if (inGroup) {
            t = textsFor(account.ownerChatId, null)
        }

        if (inGroup && request.action == MailViewAction.REPLY) {
            answerSafely(update.callbackQueryId, t.replyDisabled, showAlert = true)
            return
        }

        if (
            request.action in setOf(
                MailViewAction.TEXT,
                MailViewAction.BACK,
            ) &&
            update.messageId == null
        ) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = t.cannotFindMessage,
                showAlert = true,
            )
            return
        }

        val emailKey = buildString {
            append(account.ownerChatId)
            append(':')
            append(request.accountCode)
            append(':')
            append(request.uidValidity)
            append(':')
            append(request.uid)
        }

        if (!inProgressEmails.add(emailKey)) {
            answerSafely(
                callbackQueryId = update.callbackQueryId,
                text = t.alreadyProcessing,
                showAlert = false,
            )
            return
        }

        try {
            when (request.action) {
                MailViewAction.TEXT -> {
                    performActionWithAcknowledgement(
                        callbackQueryId = update.callbackQueryId,
                        text = t.openingText,
                    ) {
                        forwardingService.openTextView(
                            account = account,
                            expectedUidValidity = request.uidValidity,
                            uid = request.uid,
                            sourceMessageId = requireNotNull(
                                update.messageId,
                            ),
                            chatId = chatId,
                        )
                    }
                }

                MailViewAction.ATTACHMENTS -> {
                    performActionWithAcknowledgement(
                        callbackQueryId = update.callbackQueryId,
                        text = t.sendingAttachmentsShort,
                    ) {
                        forwardingService.sendAttachments(
                            account = account,
                            expectedUidValidity = request.uidValidity,
                            uid = request.uid,
                            chatId = chatId,
                        )
                    }
                }

                MailViewAction.READ -> {
                    val marked = forwardingService.markAsRead(
                        account = account,
                        expectedUidValidity = request.uidValidity,
                        uid = request.uid,
                    )
                    answerSafely(
                        callbackQueryId = update.callbackQueryId,
                        text = if (marked) t.markedRead else t.messageGone,
                        showAlert = !marked,
                    )
                }

                MailViewAction.REPLY -> {
                    startReply(update.callbackQueryId, account, request.uidValidity, request.uid, t)
                }

                MailViewAction.BACK -> {
                    performActionWithAcknowledgement(
                        callbackQueryId = update.callbackQueryId,
                        text = null,
                    ) {
                        forwardingService.returnToSummary(
                            account = account,
                            expectedUidValidity = request.uidValidity,
                            uid = request.uid,
                            sourceMessageId = requireNotNull(
                                update.messageId,
                            ),
                            chatId = chatId,
                        )
                    }
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn(
                "Failed to process action {} for email UID {} from mailbox {}: {}",
                request.action,
                request.uid,
                account.username,
                exception.javaClass.simpleName,
            )

            runCatching {
                controlClient.sendMessage(
                    chatId = chatId,
                    text = t.actionFailed,
                )
            }
        } finally {
            inProgressEmails.remove(emailKey)
        }
    }

    // Бот не переписывается: отвечает на команды /start, /help, /mailboxes,
    // на остальное - короткой подсказкой. Только в личном чате.
    private suspend fun handleMessage(
        message: TelegramMessageUpdate,
    ) {
        if (message.chatType == "group" || message.chatType == "supergroup") {
            handleGroupMessage(message)
            return
        }
        if (message.chatType != "private") return
        touchUser(message.fromUserId, message.languageCode, message.username, message.firstName)
        val t = textsFor(message.fromUserId, message.languageCode)

        message.payment?.let { payment ->
            handlePayment(message.chatId, message.fromUserId, payment, t)
            return
        }

        val fullText = message.text?.trim().orEmpty()
        val firstToken = fullText.substringBefore(' ').substringBefore('\n')
        val command = firstToken
            .substringBefore('@')
            .lowercase()
            .takeIf { fullText.isNotEmpty() }

        val admin = adminService?.takeIf { service -> service.isAdmin(message.fromUserId) }
        if (admin != null && command != null && handleAdminCommand(admin, message.chatId, message.fromUserId, command, fullText.removePrefix(firstToken).trim())) {
            return
        }

        // Ответ на письмо: ждем текст после нажатия "Ответить"
        val replies = replyService
        if (replies != null && replies.isWaiting(message.fromUserId)) {
            when {
                command == "/cancel" -> {
                    replies.cancel(message.fromUserId)
                    sendPlain(message.chatId, t.replyCancelled)
                    return
                }
                message.text == null -> {
                    sendPlain(message.chatId, t.replyTextOnly)
                    return
                }
                !fullText.startsWith("/") -> {
                    sendReply(replies, message.chatId, message.fromUserId, message.text, t)
                    return
                }
            }
        }

        if (command == "/cancel") {
            sendPlain(message.chatId, t.nothingToCancel)
            return
        }

        if (command == "/delete_my_data" || command == "/delete_my_data_confirm") {
            handleDeleteData(message.chatId, message.fromUserId, confirmed = command == "/delete_my_data_confirm", t = t)
            return
        }

        if (command == "/language") {
            askLanguage(message.chatId)
            return
        }

        if (command == "/premium") {
            sendPremium(message.chatId, message.fromUserId, t)
            return
        }

        if (command == "/paysupport") {
            sendPlain(message.chatId, t.billing.paySupport(subscriptions?.config?.supportContact))
            return
        }

        // Ссылка-приглашение: t.me/бот?start=vip_код приходит как "/start vip_код"
        val startArgument = fullText.removePrefix(firstToken).trim()
        if (command == "/start" && startArgument.startsWith(SubscriptionService.INVITE_PREFIX)) {
            redeemInvite(message.chatId, message.fromUserId, startArgument, t)
        }
        if (command == "/start" && startArgument.startsWith(ReferralService.PREFIX)) {
            registerReferral(message.fromUserId, startArgument)
        }

        if (command == "/referral") {
            sendReferral(message.chatId, message.fromUserId, t)
            return
        }

        val text = when (command) {
            "/start" -> buildString {
                append(t.start())
                if (subscriptions?.enabled == true) append("\n").append(t.billing.startPremiumLine)
                if (referrals?.enabled == true) append("\n").append(t.billing.startReferralLine)
            }
            "/help" -> t.help(replyEnabled = replyService != null)
            "/mailboxes" -> mailboxesText(message.fromUserId, t)
            else -> t.defaultReply
        }

        try {
            if (miniAppUrl != null) {
                controlClient.sendMessageWithWebAppButton(
                    chatId = message.chatId,
                    text = text,
                    buttonText = t.openApp,
                    webAppUrl = miniAppUrl,
                )
            } else {
                controlClient.sendMessage(chatId = message.chatId, text = text)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to answer message from {}: {}", message.fromUserId, exception.javaClass.simpleName)
        }
    }

    // true - команда администратора обработана
    private suspend fun handleAdminCommand(
        admin: AdminService,
        chatId: Long,
        adminId: Long,
        command: String,
        argument: String,
    ): Boolean {
        val reply: String = try {
            when (command) {
                "/admin", "/stats" -> admin.statsText()
                "/broadcast" -> admin.prepareBroadcast(adminId, argument)
                "/broadcast_cancel" -> admin.cancelBroadcast(adminId)
                "/broadcast_send" -> {
                    sendPlain(chatId, "Начинаю рассылку. Пришлю итог, когда закончу.")
                    admin.sendBroadcast(adminId)
                }
                "/backup" -> admin.backupText()
                "/grant" -> admin.grantText(argument)
                "/revoke" -> admin.revokeText(argument)
                "/invite" -> admin.inviteText(adminId, argument)
                "/subs" -> admin.subscriptionsText()
                "/users" -> admin.usersText()
                "/refs" -> admin.referralsText()
                "/refund" -> admin.refundText(argument)
                else -> return false
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Admin command {} failed: {}", command, exception.javaClass.simpleName)
            "Команда не выполнена: ${exception.javaClass.simpleName}. Подробности в логе сервера."
        }

        sendPlain(chatId, reply)
        return true
    }

    private suspend fun startReply(
        callbackQueryId: String,
        account: MailAccountConfig,
        uidValidity: Long,
        uid: Long,
        t: Texts,
    ) {
        val replies = replyService
        if (replies == null) {
            answerSafely(callbackQueryId, t.replyDisabled, showAlert = true)
            return
        }

        when (val result = replies.start(account, uidValidity, uid)) {
            is MailReplyService.StartResult.Ready -> {
                answerSafely(callbackQueryId, null, showAlert = false)
                controlClient.sendMessageForceReply(
                    chatId = account.ownerChatId,
                    text = t.replyPrompt(
                        subject = result.subject.ifBlank { t.noSubject }.take(200),
                        recipients = result.recipients.take(300),
                        mailbox = account.username,
                    ),
                    placeholder = t.replyPlaceholder,
                )
            }
            MailReplyService.StartResult.NotFound -> answerSafely(
                callbackQueryId, t.messageGone, showAlert = true,
            )
            MailReplyService.StartResult.NoRecipient -> answerSafely(
                callbackQueryId, t.replyNoRecipient, showAlert = true,
            )
            MailReplyService.StartResult.Unsupported -> answerSafely(
                callbackQueryId, t.replyUnsupported, showAlert = true,
            )
        }
    }

    private suspend fun sendReply(
        replies: MailReplyService,
        chatId: Long,
        userId: Long,
        body: String,
        t: Texts,
    ) {
        val text = when (val result = replies.send(userId, body)) {
            is MailReplyService.SendResult.Sent -> t.replySent(result.recipients)
            MailReplyService.SendResult.NoPending -> t.replyExpired
            MailReplyService.SendResult.TooLong -> t.replyTooLong(MailReplyService.MAX_BODY_LENGTH)
            MailReplyService.SendResult.MailboxGone -> t.replyMailboxGone
            MailReplyService.SendResult.AuthFailed -> t.replyAuthFailed
            MailReplyService.SendResult.Failed -> t.replyFailed
        }
        sendPlain(chatId, text)
    }

    // Сначала предупреждение, удаление - только отдельной командой подтверждения
    private suspend fun handleDeleteData(chatId: Long, userId: Long, confirmed: Boolean, t: Texts) {
        val service = userData ?: return
        val text = try {
            if (!confirmed) {
                t.deleteWarning(service.mailboxCount(userId))
            } else {
                t.deleteDone(service.deleteAll(userId), service.backupKeepDays)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to delete data of user {}: {}", userId, exception.javaClass.simpleName)
            t.deleteFailed
        }
        sendPlain(chatId, text)
    }

    private suspend fun sendPlain(chatId: Long, text: String) {
        try {
            controlClient.sendMessage(chatId = chatId, text = text)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to send message to {}: {}", chatId, exception.javaClass.simpleName)
        }
    }

    // Язык: из текущего сообщения Telegram, иначе последний известный
    // Язык ответа: выбранный вручную (/language), иначе из этого сообщения Telegram, иначе сохраненный
    private suspend fun textsFor(userId: Long, languageCode: String?): Texts {
        val users = botUsers ?: return Lang.fromCode(languageCode).texts
        return runCatching { users.resolve(userId, languageCode) }.getOrElse { Lang.fromCode(languageCode) }.texts
    }

    /** Кнопки выбора языка; вопрос на двух языках, чтобы его понял любой. */
    private suspend fun askLanguage(chatId: Long) {
        try {
            controlClient.sendMessageWithButtons(
                chatId = chatId,
                text = LANGUAGE_PROMPT,
                buttons = listOf(
                    TelegramInlineButton(text = "🇷🇺 Русский", callbackData = LANGUAGE_CALLBACK_PREFIX + "ru"),
                    TelegramInlineButton(text = "🇬🇧 English", callbackData = LANGUAGE_CALLBACK_PREFIX + "en"),
                ),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to send language choice to {}: {}", chatId, exception.javaClass.simpleName)
        }
    }

    // Нажали "Русский" или "English": запоминаем выбор, переводим меню команд в этом чате
    private suspend fun chooseLanguage(update: TelegramCallbackQueryUpdate, code: String) {
        val lang = if (code == "en") Lang.EN else Lang.RU
        val users = botUsers
        if (users == null) {
            answerSafely(update.callbackQueryId, null, showAlert = false)
            return
        }

        users.setLanguage(update.fromUserId, lang)
        val texts = lang.texts
        answerSafely(update.callbackQueryId, texts.languageSet, showAlert = false)

        runCatching {
            controlClient.setMyCommands(
                commands = botCommands(texts, billingEnabled = subscriptions?.enabled == true),
                chatId = update.fromUserId,
            )
        }

        val chatId = update.chatId ?: update.fromUserId
        try {
            if (miniAppUrl != null) {
                controlClient.sendMessageWithWebAppButton(
                    chatId = chatId,
                    text = texts.languageSet,
                    buttonText = texts.openApp,
                    webAppUrl = miniAppUrl,
                )
            } else {
                controlClient.sendMessage(chatId = chatId, text = texts.languageSet)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to confirm language for {}: {}", update.fromUserId, exception.javaClass.simpleName)
        }
    }

    private suspend fun touchUser(userId: Long, languageCode: String?, username: String? = null, firstName: String? = null) {
        try {
            botUsers?.touch(userId, languageCode, username, firstName)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.debug("Failed to record bot user {}: {}", userId, exception.javaClass.simpleName)
        }
    }

    private suspend fun mailboxesText(ownerChatId: Long, t: Texts): String {
        val service = accountService ?: return t.mailboxesAppOnly
        val accounts = service.list(ownerChatId)

        if (accounts.isEmpty()) {
            return t.mailboxesEmpty
        }

        val overLimit = quota?.overLimitIds(ownerChatId).orEmpty()
        val limit = quota?.limit(ownerChatId) ?: accounts.size

        return buildString {
            appendLine(t.mailboxesTitle)
            accounts.forEach { account ->
                val health = service.health(account.id)
                val (mark, status) = if (account.filters.paused) {
                    "⏸" to t.statusPaused
                } else if (account.id in overLimit) {
                    "🔒" to t.billing.statusOverLimit(limit)
                } else when (health.status) {
                    MailboxStatus.OK -> "🟢" to t.statusOk
                    MailboxStatus.CONNECTING -> "🟡" to t.statusConnecting
                    MailboxStatus.ERROR -> "🔴" to (health.problem?.text(t)?.replaceFirstChar(Char::lowercase) ?: t.statusErrorFallback)
                }
                appendLine()
                append(mark).append(' ').append(account.username)
                if (account.name != account.username) append(" (").append(account.name).append(')')
                appendLine()
                append("    ").append(status)
                account.groupChatId?.let {
                    appendLine()
                    append("    👥 ").append(account.groupTitle ?: "group")
                }
            }
            appendLine()
            appendLine()
            append(t.mailboxesFooter)
        }
    }

    // Тариф и кнопки оплаты
    private suspend fun sendPremium(chatId: Long, userId: Long, t: Texts) {
        val service = subscriptions
        if (service == null || !service.enabled) {
            sendPlain(chatId, t.billing.billingOff)
            return
        }

        val offers = service.config.offersOnSale.filter { offer -> service.canBuy(userId, offer) }
        val text = t.billing.premium(service.status(userId), service.config, offers)
        val lang = if (t === Lang.EN.texts) Lang.EN else Lang.RU
        val buttons = offers.mapNotNull { offer ->
            val link = runCatching { invoiceLinks?.link(offer, lang) }
                .onFailure { exception -> logger.warn("Failed to create invoice link for {}: {}", offer, exception.javaClass.simpleName) }
                .getOrNull()
                ?: return@mapNotNull null
            TelegramInlineButton(text = t.billing.buyButton(offer, service.config.price(offer)), url = link)
        }

        try {
            if (buttons.isEmpty()) {
                controlClient.sendMessage(chatId = chatId, text = text)
            } else {
                controlClient.sendMessageWithButtons(chatId = chatId, text = text, buttons = buttons, perRow = 1)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to send plans to {}: {}", chatId, exception.javaClass.simpleName)
        }
    }

    // Перед оплатой Telegram спрашивает бота, можно ли ее принять (ответить нужно за 10 секунд)
    private suspend fun handlePreCheckout(query: TelegramPreCheckoutQuery) {
        val service = subscriptions
        val offer = service?.let {
            runCatching { it.validateCheckout(query.fromUserId, query.payload, query.currency, query.totalAmount) }.getOrNull()
        }
        val t = textsFor(query.fromUserId, query.languageCode)
        try {
            controlClient.answerPreCheckoutQuery(
                queryId = query.id,
                ok = offer != null,
                errorMessage = if (offer == null) t.billing.checkoutRejected else null,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to answer pre-checkout query from {}: {}", query.fromUserId, exception.javaClass.simpleName)
        }
        logger.info("Pre-checkout from {} for {}: {}", query.fromUserId, query.payload, if (offer != null) "accepted" else "rejected")
    }

    // Оплата прошла: подключаем тариф
    private suspend fun handlePayment(chatId: Long, userId: Long, payment: TelegramSuccessfulPayment, t: Texts) {
        val service = subscriptions
        if (service == null) {
            logger.warn("Payment {} from {} received, but subscriptions are not configured", payment.chargeId, userId)
            sendPlain(chatId, t.billing.paymentUnknown)
            return
        }

        val subscription = try {
            service.onPayment(
                userId = userId,
                payload = payment.payload,
                amount = payment.totalAmount,
                chargeId = payment.chargeId,
                subscriptionExpiresAt = payment.subscriptionExpirationDate?.let(Instant::ofEpochSecond),
                firstRecurring = payment.firstRecurring,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to process payment {} from {}: {}", payment.chargeId, userId, exception.javaClass.simpleName)
            null
        }

        if (subscription == null) {
            sendPlain(chatId, t.billing.paymentUnknown)
            return
        }
        // Об автопродлениях не пишем: человек и так видит списание в Telegram
        if (!payment.recurring || payment.firstRecurring) {
            sendPlain(chatId, t.billing.paymentDone(subscription.plan, subscription.expiresAt, subscription.recurring))
        }
    }

    // В группах бот молчит: отвечает только на привязку ящика и следит за сменой ID группы
    private suspend fun handleGroupMessage(message: TelegramMessageUpdate) {
        val links = groupLinks ?: return

        message.migrateToChatId?.let { newChatId ->
            runCatching { links.onMigrated(message.chatId, newChatId) }
                .onFailure { exception -> logger.warn("Failed to move group {}: {}", message.chatId, exception.javaClass.simpleName) }
            return
        }

        val text = message.text?.trim() ?: return
        val firstToken = text.substringBefore(' ')
        if (!firstToken.substringBefore('@').equals("/start", ignoreCase = true)) return
        val code = text.removePrefix(firstToken).trim()
        if (!code.startsWith(GroupLinkService.CODE_PREFIX)) return

        val t = textsFor(message.fromUserId, message.languageCode)

        // Анонимный админ пишет от имени группы: кто он, бот не знает, поэтому владельца не узнать.
        // Код не тратим: после смены профиля ссылку можно открыть снова
        if (message.senderChatId != null || message.fromUserId == ANONYMOUS_ADMIN_ID) {
            sendPlain(message.chatId, t.billing.groupAnonymous)
            return
        }

        val reply = try {
            when (val result = links.link(code, message.fromUserId, message.chatId, message.chatTitle)) {
                is GroupLinkService.LinkResult.Linked -> t.billing.groupLinked(result.account.name, result.account.username)
                GroupLinkService.LinkResult.Expired -> t.billing.groupLinkExpired
                GroupLinkService.LinkResult.WrongUser -> t.billing.groupWrongUser
                GroupLinkService.LinkResult.NeedBusiness -> t.billing.groupNeedBusiness
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to link group {}: {}", message.chatId, exception.javaClass.simpleName)
            t.actionFailed
        }
        sendPlain(message.chatId, reply)
    }

    // Бота удалили из группы: письма привязанных ящиков возвращаются владельцам в личный чат
    private suspend fun handleChatMember(update: TelegramChatMemberUpdate) {
        val links = groupLinks ?: return
        if (update.chatType != "group" && update.chatType != "supergroup") return
        if (update.newStatus != "left" && update.newStatus != "kicked") return

        val unlinked = try {
            links.onRemovedFromGroup(update.chatId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to unlink group {}: {}", update.chatId, exception.javaClass.simpleName)
            return
        }
        unlinked.forEach { account ->
            val t = textsFor(account.ownerChatId, null)
            sendPlain(account.ownerChatId, t.billing.groupRemoved(account.name, account.username, update.chatTitle ?: "?"))
        }
    }

    // Новый пользователь пришел по ссылке друга: сообщаем другу
    private suspend fun registerReferral(userId: Long, code: String) {
        val service = referrals ?: return
        val result = try {
            service.register(userId, code)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to register referral for {}: {}", userId, exception.javaClass.simpleName)
            return
        }
        if (result is ReferralService.RegisterResult.Registered) {
            sendPlain(result.referrerId, textsFor(result.referrerId, null).billing.referralNewcomer)
        }
    }

    // Своя ссылка-приглашение и счетчик друзей
    private suspend fun sendReferral(chatId: Long, userId: Long, t: Texts) {
        val service = referrals
        val username = botUsername()
        if (service == null || !service.enabled || username == null) {
            sendPlain(chatId, t.billing.referralOff)
            return
        }

        val link = "https://t.me/$username?start=${service.code(userId)}"
        val stats = service.stats(userId)
        val text = t.billing.referralInfo(link, stats.invited, stats.qualified, stats.goal, stats.towardsNext, stats.rewards)
        val share = "https://t.me/share/url?url=" + java.net.URLEncoder.encode(link, Charsets.UTF_8) +
            "&text=" + java.net.URLEncoder.encode(t.billing.referralShareText, Charsets.UTF_8)
        try {
            controlClient.sendMessageWithButtons(
                chatId = chatId,
                text = text,
                buttons = listOf(TelegramInlineButton(text = t.billing.referralShareButton, url = share)),
                perRow = 1,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to send referral link to {}: {}", chatId, exception.javaClass.simpleName)
        }
    }

    private suspend fun redeemInvite(chatId: Long, userId: Long, code: String, t: Texts) {
        val service = subscriptions ?: return
        val text = try {
            when (val result = service.redeemInvite(code, userId)) {
                is SubscriptionService.InviteResult.Activated ->
                    t.billing.inviteActivated(result.subscription.plan, result.subscription.expiresAt)
                SubscriptionService.InviteResult.NotFound -> t.billing.inviteNotFound
                SubscriptionService.InviteResult.AlreadyUsed -> t.billing.inviteUsed
                SubscriptionService.InviteResult.AlreadyYours -> t.billing.inviteAlreadyYours
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Failed to redeem invite for {}: {}", userId, exception.javaClass.simpleName)
            t.actionFailed
        }
        sendPlain(chatId, text)
    }

    private suspend fun performActionWithAcknowledgement(
        callbackQueryId: String,
        text: String?,
        action: suspend () -> Unit,
    ) = supervisorScope {
        launch {
            answerSafely(
                callbackQueryId = callbackQueryId,
                text = text,
                showAlert = false,
            )
        }

        action()
    }

    private suspend fun answerSafely(
        callbackQueryId: String,
        text: String?,
        showAlert: Boolean,
    ) {
        try {
            withTimeoutOrNull(CALLBACK_ANSWER_TIMEOUT_MILLIS) {
                controlClient.answerCallbackQuery(
                    callbackQueryId = callbackQueryId,
                    text = text,
                    showAlert = showAlert,
                )
            }
        } catch (exception: TelegramApiException) {
            if (
                !exception.description.contains(
                    other = "query is too old",
                    ignoreCase = true,
                )
            ) {
                logger.warn(
                    "Failed to answer callback query: TelegramApiException",
                )
            }
        } catch (exception: Exception) {
            logger.warn(
                "Failed to answer callback query: {}",
                exception.javaClass.simpleName,
            )
        }
    }

    private companion object {
        const val LANGUAGE_CALLBACK_PREFIX = "lang:"
        // @GroupAnonymousBot: от его имени приходят сообщения анонимных админов групп
        const val ANONYMOUS_ADMIN_ID = 1_087_968_824L
        const val LONG_POLL_TIMEOUT_SECONDS = 25
        const val POLL_RETRY_DELAY_MILLIS = 1_000L
        const val CALLBACK_ANSWER_TIMEOUT_MILLIS = 5_000L
    }
}
