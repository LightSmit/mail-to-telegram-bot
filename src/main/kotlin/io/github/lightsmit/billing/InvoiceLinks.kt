package io.github.lightsmit.billing

import io.github.lightsmit.i18n.Lang
import java.util.concurrent.ConcurrentHashMap

/**
 * Ссылки на оплату звездами. Ссылка одна на тариф и язык и подходит любому пользователю:
 * кто заплатил, определяем по сообщению об оплате. Поэтому создаем каждую один раз.
 */
class InvoiceLinks(
    private val create: suspend (Offer, Lang) -> String,
) {
    private val cache = ConcurrentHashMap<Pair<Offer, Lang>, String>()

    suspend fun link(offer: Offer, lang: Lang): String {
        val key = offer to lang
        cache[key]?.let { link -> return link }
        return create(offer, lang).also { link -> cache[key] = link }
    }
}
