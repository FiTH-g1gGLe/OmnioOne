package com.omnioone.messages.helpers

import com.omnioone.messages.models.Message

/**
 * Omnio One: iPhone and Google Messages send reactions to SMS users as plain texts, like
 *   Liked "See you at 8"   or   👍 to "See you at 8"   or   Removed a like from "See you at 8"
 * This spots them so they can be shown as a small emoji on the message they react to.
 */
object OmnioReactions {

    private data class Parsed(val emoji: String, val quoted: String, val removed: Boolean)

    private val iphoneWords = linkedMapOf(
        "liked" to "👍", "loved" to "❤️", "laughed at" to "😂", "emphasized" to "‼️",
        "emphasised" to "‼️", "disliked" to "👎", "questioned" to "❓"
    )

    private val iphoneRemoved = mapOf(
        "like" to "👍", "heart" to "❤️", "laugh" to "😂", "exclamation" to "‼️",
        "exclamation mark" to "‼️", "dislike" to "👎", "question mark" to "❓"
    )

    private val quoteChars = Regex("[“”„‟«»\"]|(?<=\\s|^)['‘’]|['‘’](?=\\s|$)")

    private fun normalise(text: String) = text.replace(quoteChars, "\"").replace(Regex("\\s+"), " ").trim()

    private fun parse(rawBody: String): Parsed? {
        val body = normalise(rawBody)
        if (!body.endsWith("\"") || body.count { it == '"' } < 2) return null
        val firstQuote = body.indexOf('"')
        val head = body.substring(0, firstQuote).trim()
        val quoted = body.substring(firstQuote + 1, body.length - 1).trim()
        if (quoted.isEmpty()) return null
        val lower = head.lowercase()

        iphoneWords[lower]?.let { return Parsed(it, quoted, false) }

        Regex("^removed (?:a |an )?(.+?) from$").find(lower)?.let { match ->
            val word = match.groupValues[1]
            val emoji = iphoneRemoved[word] ?: word.takeIf { isEmoji(it) } ?: return null
            return Parsed(emoji, quoted, true)
        }

        // Google Messages / iOS 17+:  "👍 to"  or  "Reacted 👍 to"
        Regex("^(?:reacted )?(.+?) to$", RegexOption.IGNORE_CASE).find(head)?.let { match ->
            val emoji = match.groupValues[1].trim()
            if (isEmoji(emoji)) return Parsed(emoji, quoted, false)
        }
        return null
    }

    /** A short "👍 to “…”" version of a reaction text, or null if it isn't one. */
    fun describe(body: String): String? {
        val parsed = parse(body) ?: return null
        return if (parsed.removed) "Removed ${parsed.emoji} from “${parsed.quoted}”" else "${parsed.emoji} to “${parsed.quoted}”"
    }

    /** True when the text has no letters or digits, i.e. it's an emoji (or emoji sequence). */
    private fun isEmoji(text: String) = text.isNotBlank() && text.length <= 16 && text.none { it.isLetterOrDigit() }

    private fun matches(body: String, quoted: String): Boolean {
        val b = normalise(body)
        if (b.equals(quoted, true)) return true
        val trimmed = quoted.removeSuffix("…").removeSuffix("...").trim()
        return trimmed.length >= 10 && trimmed.length < quoted.length && b.startsWith(trimmed, true)
    }

    /**
     * @return hidden reaction message ids, and target message id -> emojis to show on it.
     */
    fun process(messages: List<Message>): Pair<Set<Long>, Map<Long, List<String>>> {
        val hidden = HashSet<Long>()
        val reactions = LinkedHashMap<Long, MutableList<String>>()
        messages.forEachIndexed { index, message ->
            if (message.isMMS && message.body.isEmpty()) return@forEachIndexed
            val parsed = parse(message.body) ?: return@forEachIndexed
            // react to the most recent earlier message with that text
            val target = (index - 1 downTo 0).asSequence()
                .map { messages[it] }
                .firstOrNull { it.id !in hidden && matches(it.body, parsed.quoted) }
                ?: return@forEachIndexed
            hidden.add(message.id)
            val list = reactions.getOrPut(target.id) { ArrayList() }
            if (parsed.removed) list.remove(parsed.emoji) else if (parsed.emoji !in list) list.add(parsed.emoji)
        }
        return hidden to reactions.filterValues { it.isNotEmpty() }
    }
}
