package eu.woshicado.circletoshare.qr

import java.net.URLEncoder

// `scheme:` followed by at least one non-blank character. Ordinary prose with
// a colon in it ("Note: buy milk") fails here: its colon is followed by a
// space.
private val URI_SHAPE = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):\\S")

// Scheme-shaped payloads that nothing can ACTION_VIEW. The first group are
// QR data formats rather than URIs (Wi-Fi join codes, contact cards,
// bookmarks, e-mail drafts, vCard/vEvent blocks starting "BEGIN:"). The
// second are real schemes this app must never launch from a scanned code:
// `file:` can't leave the process at all, and the rest only make sense inside
// the app that minted them.
private val UNOPENABLE_SCHEMES = setOf(
    "wifi", "mecard", "bizcard", "mebkm", "matmsg", "begin",
    "file", "content", "javascript", "data"
)

// Wrappers some generators put around a plain link. "URL:https://…" and
// "URI:https://…" are bare prefixes; "URLTO:<title>:https://…" carries a
// (possibly empty) title first. The link is what's inside.
private val URL_WRAPPER = Regex("^ur[li]:", RegexOption.IGNORE_CASE)
private val URL_TO = Regex("^urlto:([^:]*):(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
// The first "scheme://…" in a URLTO payload, whether it comes straight after
// the prefix (title left out altogether) or after a title — which may have
// colons of its own ("URLTO:Docs: Home:https://…").
private val URL_TO_WEB_LINK = Regex(
    "^urlto:(?:.*?:)??([a-zA-Z][a-zA-Z0-9+.-]*://.*)$",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

// The QR text-message format, "SMSTO:<number>:<message>" — scheme-shaped, but
// not the `smsto:` URI it resembles: handed over verbatim, the message ends
// up as part of the recipient.
private val SMS_TO = Regex("^(?:sms|mms)to:([^:?]*):(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/**
 * The URI to ACTION_VIEW for a scanned payload, or null when [text] isn't
 * something an app could open — plain text, or one of the structured non-URI
 * formats QR codes also carry. Those can still be copied.
 *
 * Usually the trimmed payload itself: http(s), mailto, tel, geo, sms, or an
 * app's own deep-link scheme. Raw spaces are let through — payment and
 * authenticator codes routinely carry unencoded names — but a line break
 * never occurs in a URI, so a multi-line payload is text however it starts.
 */
fun qrOpenUri(text: String): String? {
    val trimmed = text.trim()
    val scheme = URI_SHAPE.find(trimmed)?.groupValues?.get(1)?.lowercase() ?: return null
    if (scheme in UNOPENABLE_SCHEMES) return null

    // A wrapper is openable exactly when what it wraps is. (Each pass strips
    // a prefix, so this ends.)
    URL_WRAPPER.find(trimmed)?.let { wrapper ->
        return qrOpenUri(trimmed.substring(wrapper.range.last + 1))
    }
    if (scheme == "urlto") {
        // By the format the link follows the title, but nothing escapes a
        // colon inside the title, and some generators leave the title out
        // altogether. A "scheme://" is unmistakable either way, and its
        // verdict is final; only for payloads without one
        // ("URLTO:Call me:tel:…") is the title taken to end at the first
        // colon.
        URL_TO_WEB_LINK.matchEntire(trimmed)?.let { match ->
            return qrOpenUri(match.groupValues[1])
        }
        return URL_TO.matchEntire(trimmed)?.let { qrOpenUri(it.groupValues[2]) }
    }

    SMS_TO.matchEntire(trimmed)?.let { match ->
        val (number, message) = match.destructured
        if (number.isBlank()) return null
        // URLEncoder does form encoding; a URI query wants %20, not "+".
        val body = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
        return if (body.isEmpty()) "sms:${number.trim()}" else "sms:${number.trim()}?body=$body"
    }

    return if (trimmed.any { it == '\n' || it == '\r' }) null else trimmed
}
