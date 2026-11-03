package io.github.lightsmit.mail

/**
 * Находит в письме код подтверждения (одноразовый код входа, код из SMS-рассылки банка и т.п.),
 * чтобы показать его в карточке с кнопкой «Скопировать».
 *
 * Код ищем только рядом со словами вроде «код», «code», «пароль», «PIN»:
 * так в карточку не попадут номера заказов, суммы и даты.
 */
object OtpExtractor {

    // Слова, после которых обычно идет код. Английские - целыми словами (иначе "pin" найдется
    // в "shipping"), русские - по началу слова ("кода", "подтверждения")
    private val keyword = Regex(
        """(?iu)(?<!\p{L})(?:(?:code|passcode|password|pin|otp|token|verification|confirmation|confirm|one[- ]time)(?!\p{L})|код|пароль|подтвержд|верификац|одноразов)""",
    )

    // Сам код: 4-8 цифр, допускаются разделители "123 456" и "123-456".
    // Не часть телефона, суммы, даты или номера карты: вокруг нет других цифр, точек и двоеточий
    private val candidate = Regex(
        """(?<![\p{L}\d.,:/@#+-])(?<!\d[ -])(\d{3,4}[ -]\d{3,4}|\d{4,8})(?![\p{L}\d]|[.,:/]\d|[ -]\d)""",
    )

    // Сколько символов после ключевого слова смотрим
    private const val WINDOW = 80
    // Сколько текста письма просматриваем: код всегда в начале
    private const val BODY_LIMIT = 3_000

    fun find(subject: String?, body: String?): String? {
        subject?.let { text -> findIn(text) }?.let { code -> return code }
        return body?.take(BODY_LIMIT)?.let { text -> findIn(text) }
    }

    private fun findIn(text: String): String? {
        for (match in keyword.findAll(text)) {
            val start = match.range.last + 1
            val window = text.substring(start, minOf(text.length, start + WINDOW))
            val code = candidate.findAll(window)
                .map { found -> found.value.replace(" ", "").replace("-", "") }
                .firstOrNull { digits -> digits.length in 4..8 && !looksLikeYear(digits) }
            if (code != null) return code
        }
        return null
    }

    private fun looksLikeYear(digits: String): Boolean =
        digits.length == 4 && digits.toInt() in 1990..2099
}
