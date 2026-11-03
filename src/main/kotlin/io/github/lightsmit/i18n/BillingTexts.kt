package io.github.lightsmit.i18n

import io.github.lightsmit.billing.BillingConfig
import io.github.lightsmit.billing.Offer
import io.github.lightsmit.billing.Plan
import io.github.lightsmit.billing.PlanStatus
import io.github.lightsmit.billing.SubscriptionSource
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC)

internal fun formatDate(instant: Instant): String = dateFormat.format(instant)

/** Тексты о тарифах, оплате и кодах подтверждения. */
interface BillingTexts {
    val commandPremium: String
    val commandPaySupport: String
    val startPremiumLine: String

    fun premium(status: PlanStatus, config: BillingConfig, offers: List<Offer>): String
    fun buyButton(offer: Offer, stars: Int): String
    fun invoiceTitle(offer: Offer): String
    fun invoiceDescription(offer: Offer): String
    val invoiceLabel: String
    val checkoutRejected: String
    fun paymentDone(plan: Plan, expiresAt: Instant?, recurring: Boolean): String
    val paymentUnknown: String
    val billingOff: String
    fun paySupport(contact: String?): String

    fun inviteActivated(plan: Plan, expiresAt: Instant?): String
    val inviteNotFound: String
    val inviteUsed: String
    val inviteAlreadyYours: String

    fun expiring(plan: Plan, expiresAt: Instant, trial: Boolean): String
    fun ended(plan: Plan, trial: Boolean): String

    fun limitReached(plan: Plan, limit: Int, billingEnabled: Boolean): String
    val proFilters: String
    val proBrowse: String
    fun statusOverLimit(limit: Int): String

    fun otpLine(code: String): String
    val buttonCopyCode: String

    // Пересылка в групповой чат (Business)
    fun groupLinked(name: String, address: String): String
    val groupLinkExpired: String
    val groupWrongUser: String
    val groupNeedBusiness: String
    fun groupRemoved(name: String, address: String, group: String): String
    val groupBusinessOnly: String
    val groupAnonymous: String

    // Приглашение друзей
    val commandReferral: String
    val startReferralLine: String
    fun referralInfo(link: String, invited: Int, qualified: Int, goal: Int, towardsNext: Int, rewards: Int): String
    val referralShareButton: String
    val referralShareText: String
    val referralOff: String
    val referralNewcomer: String
    fun referralProgress(towardsNext: Int, goal: Int): String
    fun referralRewarded(goal: Int, plan: Plan?, expiresAt: Instant?): String
}

object RuBillingTexts : BillingTexts {
    override val commandPremium = "Тариф и подписка"
    override val commandPaySupport = "Вопросы об оплате"
    override val startPremiumLine = "/premium - тариф и подписка"

    override fun premium(status: PlanStatus, config: BillingConfig, offers: List<Offer>) = buildString {
        appendLine("⭐ Ваш тариф: ${status.plan.title}")
        when {
            status.admin -> appendLine("Вы администратор: все возможности доступны навсегда.")
            status.lifetime -> appendLine("Действует навсегда.")
            status.source == SubscriptionSource.TRIAL && status.expiresAt != null ->
                appendLine("Пробный период до ${formatDate(status.expiresAt)}.")
            status.recurring && status.expiresAt != null ->
                appendLine("Продлится автоматически ${formatDate(status.expiresAt)}. Отменить: в профиле Telegram, раздел «Звезды».")
            status.expiresAt != null -> appendLine("Действует до ${formatDate(status.expiresAt)}.")
        }
        appendLine()
        appendLine("🆓 Free")
        appendLine("• 1 ящик, уведомления о письмах с кнопками")
        appendLine("• последние 20 писем в приложении")
        appendLine("• пауза, фильтр рассылок, тихие часы и сводка")
        appendLine()
        appendLine("⭐ Pro")
        appendLine("• до ${Plan.PRO.maxMailboxes} ящиков")
        appendLine("• все фильтры: «только от», «не от», слова в теме")
        appendLine("• все письма в приложении: поиск, фильтры, «Отправленные»")
        appendLine("• коды подтверждения из писем с кнопкой «Скопировать»")
        appendLine("• ${config.proMonthStars}⭐ в месяц или ${config.proYearStars}⭐ в год")
        if (config.businessOnSale) {
            appendLine()
            appendLine("💼 Business")
            appendLine("• все из Pro, до ${Plan.BUSINESS.maxMailboxes} ящиков")
            appendLine("• письма ящика в групповой чат команды: кнопки работают у всех участников")
            appendLine("• ${config.businessMonthStars}⭐ в месяц или ${config.businessYearStars}⭐ в год")
        }
        if (offers.isNotEmpty()) {
            appendLine()
            append("Оплата звездами Telegram. Месячная подписка продлевается сама, ее можно отменить в любой момент. Годовая оплачивается один раз.")
        }
    }.trimEnd()

    override fun buyButton(offer: Offer, stars: Int) = when (offer) {
        Offer.PRO_MONTH -> "⭐ Pro на месяц · $stars⭐"
        Offer.PRO_YEAR -> "⭐ Pro на год · $stars⭐"
        Offer.BUSINESS_MONTH -> "💼 Business на месяц · $stars⭐"
        Offer.BUSINESS_YEAR -> "💼 Business на год · $stars⭐"
    }

    override fun invoiceTitle(offer: Offer) = when (offer) {
        Offer.PRO_MONTH -> "Pro на месяц"
        Offer.PRO_YEAR -> "Pro на год"
        Offer.BUSINESS_MONTH -> "Business на месяц"
        Offer.BUSINESS_YEAR -> "Business на год"
    }

    override fun invoiceDescription(offer: Offer) = when (offer.plan) {
        Plan.BUSINESS -> "До ${Plan.BUSINESS.maxMailboxes} ящиков, письма в групповой чат команды и все возможности Pro."
        else -> "До ${Plan.PRO.maxMailboxes} ящиков, все фильтры, поиск по письмам и коды подтверждения с кнопкой «Скопировать»."
    } + if (offer.recurring) " Продлевается каждые 30 дней, отменить можно в любой момент." else " Оплата один раз на 12 месяцев."

    override val invoiceLabel = "Подписка"
    override val checkoutRejected = "Этот тариф сейчас нельзя оформить. Откройте /premium и попробуйте еще раз."

    override fun paymentDone(plan: Plan, expiresAt: Instant?, recurring: Boolean) = buildString {
        append("🎉 Спасибо! Тариф ${plan.title} подключен")
        when {
            expiresAt == null -> append(" навсегда.")
            recurring -> append(", следующее продление ${formatDate(expiresAt)}.")
            else -> append(" до ${formatDate(expiresAt)}.")
        }
    }

    override val paymentUnknown = "Оплата получена, но не удалось понять, за какой тариф. Напишите в /paysupport, разберемся."
    override val billingOff = "Сейчас все возможности бота доступны бесплатно 🙂"
    override fun paySupport(contact: String?) =
        "💬 Вопросы об оплате и возвратах: " + (contact ?: "напишите владельцу бота") +
            ".\nУкажите дату оплаты и тариф, ответим как можно скорее."

    override fun inviteActivated(plan: Plan, expiresAt: Instant?) =
        "🎁 Подарок активирован: тариф ${plan.title} " + (expiresAt?.let { "до ${formatDate(it)}." } ?: "навсегда.")
    override val inviteNotFound = "Ссылка-приглашение не найдена. Проверьте, что скопировали ее целиком."
    override val inviteUsed = "Этой ссылкой уже воспользовались: она одноразовая."
    override val inviteAlreadyYours = "Вы уже активировали эту ссылку 🙂"

    override fun expiring(plan: Plan, expiresAt: Instant, trial: Boolean) =
        if (trial) {
            "⏳ Пробный период Pro закончится ${formatDate(expiresAt)}. Чтобы сохранить все ящики и фильтры, оформите подписку: /premium"
        } else {
            "⏳ Тариф ${plan.title} закончится ${formatDate(expiresAt)}. Продлить: /premium"
        }

    override fun ended(plan: Plan, trial: Boolean) = buildString {
        append(if (trial) "Пробный период закончился" else "Тариф ${plan.title} закончился")
        append(", теперь у вас Free. Работает 1 ящик (остальные можно поставить на паузу или удалить), ")
        append("фильтры по отправителям и словам не действуют, но сохранены. Вернуть все: /premium")
    }

    override fun limitReached(plan: Plan, limit: Int, billingEnabled: Boolean) = when {
        !billingEnabled -> "Можно подключить не больше $limit ящиков"
        plan == Plan.FREE -> "На тарифе Free можно подключить $limit ящик. Больше ящиков в Pro: /premium в боте"
        else -> "На тарифе ${plan.title} можно подключить не больше $limit ящиков"
    }

    override val proFilters = "Фильтры по отправителям и словам в теме доступны в Pro. Пауза и фильтр рассылок работают на любом тарифе."
    override val proBrowse = "Поиск, фильтр «непрочитанные», папка «Отправленные» и письма старше последних 20 доступны в Pro."
    override fun statusOverLimit(limit: Int) =
        "не работает: на тарифе Free работает $limit ящик. Поставьте лишние на паузу или оформите Pro"

    override fun otpLine(code: String) = "🔑 Код: $code"
    override val buttonCopyCode = "📋 Скопировать код"

    override fun groupLinked(name: String, address: String) =
        "✅ Письма из ящика «$name» ($address) теперь приходят в эту группу.\n\n" +
            "Кнопки под письмами может нажимать любой участник. Отключить группу можно в приложении, в настройках ящика."
    override val groupLinkExpired = "Ссылка устарела. Получите новую в приложении: настройки ящика, раздел «Групповой чат»."
    override val groupWrongUser = "Подключить группу может только владелец ящика: он получает ссылку в приложении."
    override val groupNeedBusiness = "Пересылка писем в группу доступна на тарифе Business. Подробнее: /premium в личном чате с ботом."
    override fun groupRemoved(name: String, address: String, group: String) =
        "Бота удалили из группы «$group», поэтому письма ящика «$name» ($address) снова приходят сюда."
    override val groupBusinessOnly = "Пересылка в группу доступна на тарифе Business"
    override val groupAnonymous =
        "Вы пишете анонимно, от имени группы, и бот не может проверить, что ящик ваш. " +
            "Нажмите на аватар слева от поля ввода, выберите свой профиль и откройте ссылку из приложения еще раз " +
            "(или выключите «Анонимность» в правах администратора)."

    override val commandReferral = "Пригласить друзей и получить Pro"
    override val startReferralLine = "/referral - пригласить друзей и получить Pro бесплатно"
    override fun referralInfo(link: String, invited: Int, qualified: Int, goal: Int, towardsNext: Int, rewards: Int) = buildString {
        appendLine("🎁 Приглашайте друзей: за каждых $goal друзей - месяц Pro бесплатно.")
        appendLine()
        appendLine("Ваша ссылка:")
        appendLine(link)
        appendLine()
        appendLine("Друг засчитывается, когда впервые откроет бота по вашей ссылке и подключит хотя бы один ящик.")
        appendLine()
        appendLine("Пришли по ссылке: $invited, засчитано: $qualified")
        append("До следующего месяца Pro: $towardsNext из $goal")
        if (rewards > 0) append("\nПолучено наград: $rewards")
    }
    override val referralShareButton = "📤 Поделиться ссылкой"
    override val referralShareText = "☝️ Бот, который присылает новые письма из почты прямо в Telegram. Попробуй, по этой ссылке первые 7 дней Pro бесплатно."
    override val referralOff = "Сейчас приглашения не работают."
    override val referralNewcomer = "👋 По вашей ссылке пришел новый пользователь. Он засчитается, когда подключит ящик."
    override fun referralProgress(towardsNext: Int, goal: Int) =
        "✅ Друг подключил ящик по вашей ссылке: $towardsNext из $goal до месяца Pro. Ссылка: /referral"
    override fun referralRewarded(goal: Int, plan: Plan?, expiresAt: Instant?) =
        if (plan == null) {
            "🎉 Еще $goal друзей подключились по вашей ссылке! У вас и так подписка навсегда, спасибо, что советуете бота."
        } else {
            "🎉 Еще $goal друзей подключились по вашей ссылке! Тариф ${plan.title} " +
                (expiresAt?.let { "действует до ${formatDate(it)}." } ?: "действует навсегда.")
        }
}

object EnBillingTexts : BillingTexts {
    override val commandPremium = "Plan and subscription"
    override val commandPaySupport = "Payment questions"
    override val startPremiumLine = "/premium - plan and subscription"

    override fun premium(status: PlanStatus, config: BillingConfig, offers: List<Offer>) = buildString {
        appendLine("⭐ Your plan: ${status.plan.title}")
        when {
            status.admin -> appendLine("You are an admin: everything is available forever.")
            status.lifetime -> appendLine("Valid forever.")
            status.source == SubscriptionSource.TRIAL && status.expiresAt != null ->
                appendLine("Free trial until ${formatDate(status.expiresAt)}.")
            status.recurring && status.expiresAt != null ->
                appendLine("Renews automatically on ${formatDate(status.expiresAt)}. Cancel in your Telegram profile, \"Stars\".")
            status.expiresAt != null -> appendLine("Valid until ${formatDate(status.expiresAt)}.")
        }
        appendLine()
        appendLine("🆓 Free")
        appendLine("• 1 mailbox, email notifications with buttons")
        appendLine("• the latest 20 emails in the app")
        appendLine("• pause, newsletter filter, quiet hours and digest")
        appendLine()
        appendLine("⭐ Pro")
        appendLine("• up to ${Plan.PRO.maxMailboxes} mailboxes")
        appendLine("• all filters: \"only from\", \"never from\", subject words")
        appendLine("• all emails in the app: search, filters, Sent folder")
        appendLine("• verification codes from emails with a \"Copy\" button")
        appendLine("• ${config.proMonthStars}⭐ a month or ${config.proYearStars}⭐ a year")
        if (config.businessOnSale) {
            appendLine()
            appendLine("💼 Business")
            appendLine("• everything in Pro, up to ${Plan.BUSINESS.maxMailboxes} mailboxes")
            appendLine("• mailbox emails in your team's group chat: buttons work for every member")
            appendLine("• ${config.businessMonthStars}⭐ a month or ${config.businessYearStars}⭐ a year")
        }
        if (offers.isNotEmpty()) {
            appendLine()
            append("Paid with Telegram Stars. The monthly plan renews automatically and can be cancelled at any time. The yearly plan is a one-time payment.")
        }
    }.trimEnd()

    override fun buyButton(offer: Offer, stars: Int) = when (offer) {
        Offer.PRO_MONTH -> "⭐ Pro for a month · $stars⭐"
        Offer.PRO_YEAR -> "⭐ Pro for a year · $stars⭐"
        Offer.BUSINESS_MONTH -> "💼 Business for a month · $stars⭐"
        Offer.BUSINESS_YEAR -> "💼 Business for a year · $stars⭐"
    }

    override fun invoiceTitle(offer: Offer) = when (offer) {
        Offer.PRO_MONTH -> "Pro for a month"
        Offer.PRO_YEAR -> "Pro for a year"
        Offer.BUSINESS_MONTH -> "Business for a month"
        Offer.BUSINESS_YEAR -> "Business for a year"
    }

    override fun invoiceDescription(offer: Offer) = when (offer.plan) {
        Plan.BUSINESS -> "Up to ${Plan.BUSINESS.maxMailboxes} mailboxes, emails in your team's group chat and everything in Pro."
        else -> "Up to ${Plan.PRO.maxMailboxes} mailboxes, all filters, email search and verification codes with a Copy button."
    } + if (offer.recurring) " Renews every 30 days, cancel at any time." else " One-time payment for 12 months."

    override val invoiceLabel = "Subscription"
    override val checkoutRejected = "This plan cannot be purchased right now. Open /premium and try again."

    override fun paymentDone(plan: Plan, expiresAt: Instant?, recurring: Boolean) = buildString {
        append("🎉 Thank you! ${plan.title} is active")
        when {
            expiresAt == null -> append(" forever.")
            recurring -> append(", next renewal on ${formatDate(expiresAt)}.")
            else -> append(" until ${formatDate(expiresAt)}.")
        }
    }

    override val paymentUnknown = "Payment received, but the plan could not be identified. Please write to /paysupport and we will sort it out."
    override val billingOff = "Right now everything in the bot is free 🙂"
    override fun paySupport(contact: String?) =
        "💬 Payment and refund questions: " + (contact ?: "contact the bot owner") +
            ".\nPlease include the payment date and plan, we will reply as soon as possible."

    override fun inviteActivated(plan: Plan, expiresAt: Instant?) =
        "🎁 Gift activated: ${plan.title} " + (expiresAt?.let { "until ${formatDate(it)}." } ?: "forever.")
    override val inviteNotFound = "Invite link not found. Make sure you copied the whole link."
    override val inviteUsed = "This invite link has already been used: it works only once."
    override val inviteAlreadyYours = "You have already activated this link 🙂"

    override fun expiring(plan: Plan, expiresAt: Instant, trial: Boolean) =
        if (trial) {
            "⏳ Your Pro trial ends on ${formatDate(expiresAt)}. To keep all your mailboxes and filters, subscribe: /premium"
        } else {
            "⏳ ${plan.title} ends on ${formatDate(expiresAt)}. Renew: /premium"
        }

    override fun ended(plan: Plan, trial: Boolean) = buildString {
        append(if (trial) "Your trial has ended" else "${plan.title} has ended")
        append(", you are now on Free. 1 mailbox works (pause or delete the others), ")
        append("sender and subject filters are off but saved. Get everything back: /premium")
    }

    override fun limitReached(plan: Plan, limit: Int, billingEnabled: Boolean) = when {
        !billingEnabled -> "You can connect up to $limit mailboxes"
        plan == Plan.FREE -> "Free includes $limit mailbox. Get more mailboxes with Pro: /premium in the bot"
        else -> "${plan.title} includes up to $limit mailboxes"
    }

    override val proFilters = "Sender and subject filters are available in Pro. Pause and the newsletter filter work on any plan."
    override val proBrowse = "Search, the unread filter, the Sent folder and emails beyond the latest 20 are available in Pro."
    override fun statusOverLimit(limit: Int) =
        "not working: Free includes $limit mailbox. Pause the others or get Pro"

    override fun otpLine(code: String) = "🔑 Code: $code"
    override val buttonCopyCode = "📋 Copy code"

    override fun groupLinked(name: String, address: String) =
        "✅ Emails from mailbox \"$name\" ($address) now arrive in this group.\n\n" +
            "Any member can use the buttons under emails. You can disconnect the group in the app, in the mailbox settings."
    override val groupLinkExpired = "This link has expired. Get a new one in the app: mailbox settings, \"Group chat\"."
    override val groupWrongUser = "Only the mailbox owner can connect a group: they get the link in the app."
    override val groupNeedBusiness = "Forwarding emails to a group is available on Business. Details: /premium in a private chat with the bot."
    override fun groupRemoved(name: String, address: String, group: String) =
        "The bot was removed from the group \"$group\", so emails from mailbox \"$name\" ($address) arrive here again."
    override val groupBusinessOnly = "Group forwarding is available on Business"
    override val groupAnonymous =
        "You are posting anonymously on behalf of the group, so the bot cannot check that the mailbox is yours. " +
            "Tap the avatar next to the message field, choose your own profile and open the link from the app again " +
            "(or turn off \"Remain anonymous\" in your admin rights)."

    override val commandReferral = "Invite friends and get Pro"
    override val startReferralLine = "/referral - invite friends and get Pro for free"
    override fun referralInfo(link: String, invited: Int, qualified: Int, goal: Int, towardsNext: Int, rewards: Int) = buildString {
        appendLine("🎁 Invite friends: every $goal friends give you a month of Pro for free.")
        appendLine()
        appendLine("Your link:")
        appendLine(link)
        appendLine()
        appendLine("A friend counts when they open the bot for the first time with your link and connect at least one mailbox.")
        appendLine()
        appendLine("Came with your link: $invited, counted: $qualified")
        append("Until the next month of Pro: $towardsNext of $goal")
        if (rewards > 0) append("\nRewards received: $rewards")
    }
    override val referralShareButton = "📤 Share the link"
    override val referralShareText = "☝️ A bot that sends your new emails right to Telegram. Try it: the first 7 days of Pro are free."
    override val referralOff = "Invitations are not available right now."
    override val referralNewcomer = "👋 A new user came with your link. They will count once they connect a mailbox."
    override fun referralProgress(towardsNext: Int, goal: Int) =
        "✅ A friend connected a mailbox with your link: $towardsNext of $goal until a month of Pro. Link: /referral"
    override fun referralRewarded(goal: Int, plan: Plan?, expiresAt: Instant?) =
        if (plan == null) {
            "🎉 $goal more friends joined with your link! Your subscription is already forever, thanks for recommending the bot."
        } else {
            "🎉 $goal more friends joined with your link! ${plan.title} " +
                (expiresAt?.let { "is active until ${formatDate(it)}." } ?: "is active forever.")
        }
}
