package space.koyu.enyo

import org.json.JSONObject

internal data class MessageDraft(val recipient: String, val body: String)

private val draftPatterns = listOf(
    """send\s+(?:(?:a|an)\s+)?(?:(?:text|sms)\s+)?message\s+to\s+(.+?)\s+(?:to\s+say(?:\s+that)?|that|saying)\s+(.+)""",
    """(?:message|text|tell)\s+(.+?)(?:\s*[:,]\s*|\s+(?:to\s+say(?:\s+that)?|that|saying)\s+)(.+)""",
    """let\s+(.+?)\s+know(?:\s+that)?\s+(.+)""",
    """send\s+(.+?)\s+(?:a\s+(?:quick\s+)?(?:note|message)|an\s+sms)\s+(?:to\s+say(?:\s+that)?|that|saying)\s+(.+)""",
    """(?:sende|schicke)\s+(?:eine\s+)?nachricht\s+an\s+(.+?)(?:\s*:\s*|,?\s+(?:dass|mit\s+dem\s+text)\s+)(.+)""",
    """(?:schreib(?:e)?|sag(?:e)?)\s+(.+?)(?:\s*:\s*|,?\s+dass\s+)(.+)"""
).map { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }

private val requestPrefix = Regex(
    """^(?:(?:please|bitte)\s+|(?:could|can|would)\s+you\s+(?:please\s+)?|(?:kannst|könntest)\s+du\s+(?:bitte\s+)?)""",
    RegexOption.IGNORE_CASE
)

private val messageWords = Regex(
    """\b(?:message|text|sms|tell|write|send|notify|let\s+.+?\s+know|nachricht|schreib\w*|sag\w*|send\w*|schick\w*)\b""",
    RegexOption.IGNORE_CASE
)

internal fun matchMessageDraft(query: String): MessageDraft? {
    val request = query.trim().replaceFirst(requestPrefix, "")
    return draftPatterns.firstNotNullOfOrNull { pattern ->
        pattern.matchEntire(request)?.destructured?.let { (recipient, body) ->
            messageDraft(recipient, body)
        }
    }
}

internal fun looksLikeMessageRequest(query: String): Boolean =
    query.length in 12..600 && query.count(Char::isWhitespace) >= 3 && messageWords.containsMatchIn(query)

internal fun modelMessageDraft(query: String, output: String): MessageDraft? {
    val response = output.trim()
    val jsonText = if (response.startsWith("```") && response.endsWith("```")) {
        response.substringAfter('\n', "").dropLast(3).trim()
    } else response
    val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    if (json.length() != 2) return null
    val draft = messageDraft(json.opt("recipient") as? String ?: return null, json.opt("body") as? String ?: return null)
        ?: return null
    val recipient = Regex("""(?<![\p{L}\p{N}])${Regex.escape(draft.recipient)}(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
    val match = recipient.find(query) ?: return null
    // Require literal text from the request after the recipient.
    if (!query.substring(match.range.last + 1).contains(draft.body)) return null
    return draft
}

private fun messageDraft(recipient: String, body: String): MessageDraft? {
    val name = recipient.trim().removeSurrounding("\"").trim()
    val text = body.trim().removeSurrounding("\"").removeSurrounding("“", "”").trim()
    return if (name.length in 1..100 && name.none { it.isISOControl() } && text.isNotEmpty()) MessageDraft(name, text) else null
}
