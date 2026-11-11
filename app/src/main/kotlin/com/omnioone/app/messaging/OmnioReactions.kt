package com.omnioone.app.messaging


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

    /** Same as [process], for any list of (id, body, isEmptyPictureMessage) – used by Omnio's new conversation screen. */
    fun processBodies(items: List<Triple<Long, String, Boolean>>): Pair<Set<Long>, Map<Long, List<String>>> {
        val hidden = HashSet<Long>()
        val reactions = LinkedHashMap<Long, MutableList<String>>()
        items.forEachIndexed { index, (id, body, emptyPicture) ->
            if (emptyPicture) return@forEachIndexed
            val parsed = parse(body) ?: return@forEachIndexed
            val target = (index - 1 downTo 0).asSequence()
                .map { items[it] }
                .firstOrNull { it.first !in hidden && matches(it.second, parsed.quoted) }
                ?: return@forEachIndexed
            hidden.add(id)
            val list = reactions.getOrPut(target.first) { ArrayList() }
            if (parsed.removed) list.remove(parsed.emoji) else if (parsed.emoji !in list) list.add(parsed.emoji)
        }
        return hidden to reactions.filterValues { it.isNotEmpty() }
    }

    /** One message for [processThread]; [mine] = you sent it. */
    data class Entry(val id: Long, val body: String, val emptyPicture: Boolean, val mine: Boolean)

    /** A reaction shown on a message; [mine] = you reacted. */
    data class Reaction(val emoji: String, val mine: Boolean)

    /** Like [processBodies], but remembers who reacted, so you can take your own reaction back. */
    fun processThread(items: List<Entry>): Pair<Set<Long>, Map<Long, List<Reaction>>> {
        val hidden = HashSet<Long>()
        val reactions = LinkedHashMap<Long, MutableList<Reaction>>()
        items.forEachIndexed { index, item ->
            if (item.emptyPicture) return@forEachIndexed
            val parsed = parse(item.body) ?: return@forEachIndexed
            val target = (index - 1 downTo 0).asSequence()
                .map { items[it] }
                .firstOrNull { it.id !in hidden && matches(it.body, parsed.quoted) }
                ?: return@forEachIndexed
            hidden.add(item.id)
            val list = reactions.getOrPut(target.id) { ArrayList() }
            val reaction = Reaction(parsed.emoji, item.mine)
            if (parsed.removed) list.remove(reaction) else if (reaction !in list) list.add(reaction)
        }
        return hidden to reactions.filterValues { it.isNotEmpty() }
    }

    private fun quote(body: String) = body.trim().replace(Regex("\\s+"), " ").let { if (it.length > 100) it.take(100).trimEnd() + "…" else it }

    /** The text to send to take a reaction back, in words iPhone and Google Messages understand. */
    fun removalText(emoji: String, body: String): String {
        val what = when (emoji) {
            "👍" -> "a like"
            "❤️" -> "a heart"
            "😂" -> "a laugh"
            "‼️" -> "an exclamation"
            "❓" -> "a question mark"
            "👎" -> "a dislike"
            else -> emoji
        }
        return "Removed $what from “${quote(body)}”"
    }

    /** The text to send for a reaction, in the classic words iPhone and Google Messages understand. */
    fun reactionText(emoji: String, body: String): String {
        val quoted = body.trim().replace(Regex("\\s+"), " ").let { if (it.length > 100) it.take(100).trimEnd() + "…" else it }
        val word = when (emoji) {
            "👍" -> "Liked"
            "❤️" -> "Loved"
            "😂" -> "Laughed at"
            "‼️" -> "Emphasized"
            "❓" -> "Questioned"
            "👎" -> "Disliked"
            else -> null
        }
        return if (word != null) "$word “$quoted”" else "Reacted $emoji to “$quoted”"
    }
}
