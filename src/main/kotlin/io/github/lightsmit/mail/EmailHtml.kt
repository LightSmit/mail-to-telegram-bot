package io.github.lightsmit.mail

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimePart
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.util.Base64

/**
 * HTML письма для просмотра в приложении: очищенный от скриптов, форм и обработчиков,
 * картинки из самого письма (cid:) встроены как data:. Внешние картинки остаются ссылками:
 * загружать их или нет, решает приложение (по умолчанию нет - ими отслеживают открытие).
 */
object EmailHtml {

    private const val MAX_HTML_CHARS = 400_000
    private const val MAX_INLINE_IMAGE_BYTES = 1_500_000
    private const val MAX_INLINE_IMAGES_TOTAL = 6_000_000
    private const val MAX_STYLE_CHARS = 100_000

    private val safelist: Safelist = Safelist.relaxed()
        .addTags("center", "font", "hr", "small", "big", "s", "strike", "section", "header", "footer", "article")
        .addAttributes(
            ":all",
            "style", "align", "valign", "bgcolor", "width", "height", "dir", "title", "class",
        )
        .addAttributes("table", "border", "cellpadding", "cellspacing", "background")
        .addAttributes("td", "background", "nowrap")
        .addAttributes("font", "color", "face", "size")
        .addAttributes("img", "border")
        .addProtocols("img", "src", "http", "https", "data")
        .addProtocols("a", "href", "http", "https", "mailto", "tel")
        .preserveRelativeLinks(false)

    /** Очищенный HTML письма или null, если HTML-версии нет. */
    fun view(part: Part): String? = runCatching {
        val images = mutableMapOf<String, String>()
        val html = findHtml(part, images, intArrayOf(0)) ?: return null
        clean(html, images)
    }.getOrNull()

    internal fun clean(html: String, images: Map<String, String>): String? {
        val source = Jsoup.parse(html)
        // Картинки из вложений письма: cid:xxx -> data:image/...;base64
        source.select("img[src^=cid:]").forEach { image ->
            val id = image.attr("src").removePrefix("cid:").trim('<', '>', ' ').lowercase()
            val data = images[id]
            if (data != null) image.attr("src", data) else image.removeAttr("src")
        }
        // Стили из <style> письма: Cleaner их удаляет, а без них многие рассылки разваливаются.
        // CSS в закрытой рамке приложения не выполняет код; внешние url() блокирует CSP приложения
        val styles = source.select("style").joinToString("\n") { style -> style.data() }
            .replace(Regex("@import[^;]*;?", RegexOption.IGNORE_CASE), "")
            .replace("</", "<\\/")
            .take(MAX_STYLE_CHARS)
        val cleaned: Document = Cleaner(safelist).clean(source)
        // Ссылки открываются снаружи приложения, без передачи адреса страницы
        cleaned.select("a[href]").forEach { link -> link.attr("rel", "noopener noreferrer") }
        cleaned.outputSettings().prettyPrint(false)
        val body = cleaned.body().html().trim()
        if (body.isEmpty()) return null
        val head = if (styles.isBlank()) "" else "<style>$styles</style>"
        return (head + body).take(MAX_HTML_CHARS)
    }

    // Первый HTML не во вложении; по пути собираем картинки с Content-ID
    private fun findHtml(part: Part, images: MutableMap<String, String>, total: IntArray): String? {
        val disposition = runCatching { part.disposition }.getOrNull()
        if (part.isMimeType("image/*")) {
            collectImage(part, images, total)
            return null
        }
        if (disposition.equals(Part.ATTACHMENT, ignoreCase = true)) return null
        return when {
            part.isMimeType("text/html") -> part.content as? String
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as? Multipart ?: return null
                var found: String? = null
                // Обходим все части: картинки могут идти после HTML
                for (index in 0 until multipart.count) {
                    val html = findHtml(multipart.getBodyPart(index), images, total)
                    if (found == null && html != null) found = html
                }
                found
            }
            else -> null
        }
    }

    private fun collectImage(part: Part, images: MutableMap<String, String>, total: IntArray) {
        val id = (part as? MimePart)?.contentID?.trim('<', '>', ' ')?.lowercase() ?: return
        val bytes = runCatching { part.inputStream.use { stream -> stream.readNBytes(MAX_INLINE_IMAGE_BYTES + 1) } }.getOrNull() ?: return
        if (bytes.size > MAX_INLINE_IMAGE_BYTES || total[0] + bytes.size > MAX_INLINE_IMAGES_TOTAL) return
        total[0] += bytes.size
        val type = part.contentType.substringBefore(';').trim().lowercase().ifEmpty { "image/png" }
        images[id] = "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
    }
}
