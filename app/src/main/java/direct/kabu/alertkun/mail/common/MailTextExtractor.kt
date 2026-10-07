package direct.kabu.alertkun.mail.common

import jakarta.mail.BodyPart
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part

/** Extracts only textual MIME content; attachments are never returned or stored. */
object MailTextExtractor {
    fun extract(message: Message): String? = extractPart(message)

    private fun extractPart(part: Part): String? = when {
        part.isMimeType("text/plain") -> part.content as? String
        part.isMimeType("text/html") -> (part.content as? String)?.let(::stripHtml)
        part.isMimeType("multipart/alternative") -> extractAlternative(part.content as Multipart)
        part.isMimeType("multipart/*") -> extractMultipart(part.content as Multipart)
        else -> null
    }

    private fun extractAlternative(multipart: Multipart): String? {
        var fallback: String? = null
        for (index in 0 until multipart.count) {
            val part = multipart.getBodyPart(index)
            if (isAttachment(part)) continue
            val value = extractPart(part)
            if (part.isMimeType("text/plain") && value != null) return value
            if (fallback == null) fallback = value
        }
        return fallback
    }

    private fun extractMultipart(multipart: Multipart): String? {
        for (index in 0 until multipart.count) {
            val part = multipart.getBodyPart(index)
            if (isAttachment(part)) continue
            extractPart(part)?.let { return it }
        }
        return null
    }

    private fun isAttachment(part: BodyPart): Boolean =
        Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || part.fileName != null

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<script[^>]*>.*?</script>|<style[^>]*>.*?</style>"), "")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
