package com.omnioone.app.helpers

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Website

/**
 * Omnio One: chat apps and social profiles on a contact page.
 *
 * - Chat apps (WhatsApp, Telegram, Signal, Viber, Google Meet) add their own entries to the
 *   phone's contacts, so they're found automatically.
 * - Social profiles (Instagram, Snapchat, Facebook, ...) are saved by the user as a profile
 *   link on the contact, so they sync with the contact and work in other apps too.
 */
object OmnioSocials {

    /** An action a chat app offers (e.g. WhatsApp chat or WhatsApp video). */
    data class ChatAction(val kind: Kind, val mimeType: String)

    enum class Kind { CHAT, VOICE, VIDEO }

    data class ChatApp(val name: String, val pkg: String, val color: Int, val actions: List<ChatAction>)

    /** A social network the user can add by username. */
    data class Network(
        val key: String,
        val name: String,
        val pkg: String,
        val color: Int,
        private val urlTemplate: String,
        private val domains: List<String>,
        /** Path prefix before the handle in the link, e.g. "@" for TikTok or "in/" for LinkedIn. */
        private val pathPrefix: String = "",
        /** People search link with {q} for the name, or null when the app has no search link. */
        private val searchTemplate: String? = null
    ) {
        fun searchUrl(name: String): String? = searchTemplate?.replace("{q}", android.net.Uri.encode(name))

        fun urlFor(handle: String) = urlTemplate.replace("{h}", handle)

        fun matches(url: String): Boolean {
            val host = hostOf(url)
            return domains.any { host == it || host.endsWith(".$it") }
        }

        /** The username shown under the network name. */
        fun handleFrom(url: String): String {
            val afterHost = url.substringAfter("://", url).substringAfter("/", "")
            // facebook.com/profile.php?id=123
            Regex("[?&]id=(\\d+)").find(afterHost)?.let { return it.groupValues[1] }
            val path = afterHost.substringBefore("?").trim('/')
            // opaque share links (facebook.com/share/abc) have no username in them
            if (path.startsWith("share/") || path.isEmpty()) return ""
            val handle = path.removePrefix(pathPrefix).substringBefore("/").removePrefix("@")
            return if (handle.isEmpty()) "" else "@$handle"
        }

        /** What to show under the network name: @username, or "" for links with only a number in them. */
        fun displayHandle(url: String): String {
            val handle = handleFrom(url)
            return if (key == "discord" && handle.removePrefix("@").all { it.isDigit() }) "" else handle
        }

        /** The bare username/id, for building a Messenger link from a Facebook one. */
        fun usernameFrom(url: String): String {
            val handle = handleFrom(url).removePrefix("@")
            return if (handle.lowercase() in reservedPaths) "" else handle
        }
    }

    private fun hostOf(url: String): String {
        var host = url.substringAfter("://", url).substringBefore("/").substringBefore("?").lowercase().removePrefix("www.")
        // drop mobile sub-domains (m.facebook.com) but keep short domains like m.me
        listOf("m.", "mobile.").forEach { prefix ->
            if (host.startsWith(prefix) && host.count { it == '.' } >= 2) host = host.removePrefix(prefix)
        }
        return host
    }

    private const val ITEM = "vnd.android.cursor.item/"

    private val reservedPaths = setOf("share", "login", "login.php", "profile.php", "people", "search", "home.php", "watch", "groups", "pages")

    val chatApps = listOf(
        ChatApp(
            "WhatsApp", "com.whatsapp", 0xFF25D366.toInt(), listOf(
                ChatAction(Kind.CHAT, "${ITEM}vnd.com.whatsapp.profile"),
                ChatAction(Kind.VIDEO, "${ITEM}vnd.com.whatsapp.video.call"),
                ChatAction(Kind.VOICE, "${ITEM}vnd.com.whatsapp.voip.call"),
            )
        ),
        ChatApp(
            "WhatsApp Business", "com.whatsapp.w4b", 0xFF25D366.toInt(), listOf(
                ChatAction(Kind.CHAT, "${ITEM}vnd.com.whatsapp.w4b.profile"),
                ChatAction(Kind.VIDEO, "${ITEM}vnd.com.whatsapp.w4b.video.call"),
                ChatAction(Kind.VOICE, "${ITEM}vnd.com.whatsapp.w4b.voip.call"),
            )
        ),
        ChatApp(
            "Telegram", "org.telegram.messenger", 0xFF2AABEE.toInt(), listOf(
                ChatAction(Kind.CHAT, "${ITEM}vnd.org.telegram.messenger.android.profile"),
                ChatAction(Kind.VOICE, "${ITEM}vnd.org.telegram.messenger.android.call"),
                ChatAction(Kind.VIDEO, "${ITEM}vnd.org.telegram.messenger.android.call.video"),
            )
        ),
        ChatApp(
            "Signal", "org.thoughtcrime.securesms", 0xFF3A76F0.toInt(), listOf(
                ChatAction(Kind.CHAT, "${ITEM}vnd.org.thoughtcrime.securesms.contact"),
                ChatAction(Kind.VOICE, "${ITEM}vnd.org.thoughtcrime.securesms.call"),
            )
        ),
        ChatApp(
            "Viber", "com.viber.voip", 0xFF7360F2.toInt(), listOf(
                ChatAction(Kind.CHAT, "${ITEM}vnd.com.viber.voip.viber_number_message"),
                ChatAction(Kind.VOICE, "${ITEM}vnd.com.viber.voip.viber_number_call"),
            )
        ),
        ChatApp(
            "Google Meet", "com.google.android.apps.tachyon", 0xFF00AC47.toInt(), listOf(
                ChatAction(Kind.VIDEO, "${ITEM}com.google.android.apps.tachyon.phone"),
            )
        ),
    )

    val networks = listOf(
        Network("instagram", "Instagram", "com.instagram.android", 0xFFE1306C.toInt(), "https://instagram.com/{h}", listOf("instagram.com", "instagr.am"), searchTemplate = "https://www.instagram.com/explore/search/keyword/?q={q}"),
        Network("snapchat", "Snapchat", "com.snapchat.android", 0xFFFFFC00.toInt(), "https://snapchat.com/add/{h}", listOf("snapchat.com"), "add/"),
        Network("facebook", "Facebook", "com.facebook.katana", 0xFF1877F2.toInt(), "https://facebook.com/{h}", listOf("facebook.com", "fb.com"), searchTemplate = "https://www.facebook.com/search/people/?q={q}"),
        Network("messenger", "Messenger", "com.facebook.orca", 0xFF0084FF.toInt(), "https://m.me/{h}", listOf("m.me", "messenger.com"), "t/", searchTemplate = "https://www.facebook.com/search/people/?q={q}"),
        Network("tiktok", "TikTok", "com.zhiliaoapp.musically", 0xFFFE2C55.toInt(), "https://tiktok.com/@{h}", listOf("tiktok.com"), searchTemplate = "https://www.tiktok.com/search/user?q={q}"),
        Network("x", "X", "com.twitter.android", 0xFFE7E9EA.toInt(), "https://x.com/{h}", listOf("x.com", "twitter.com"), searchTemplate = "https://x.com/search?q={q}&f=user"),
        Network("threads", "Threads", "com.instagram.barcelona", 0xFFE7E9EA.toInt(), "https://threads.net/@{h}", listOf("threads.net", "threads.com"), searchTemplate = "https://www.threads.net/search?q={q}"),
        Network("linkedin", "LinkedIn", "com.linkedin.android", 0xFF0A66C2.toInt(), "https://linkedin.com/in/{h}", listOf("linkedin.com"), "in/", searchTemplate = "https://www.linkedin.com/search/results/people/?keywords={q}"),
        Network("discord", "Discord", "com.discord", 0xFF5865F2.toInt(), "https://discord.com/users/{h}", listOf("discord.com", "discordapp.com"), "users/"),
    )

    // ---- "Find on Facebook" linking: remember which contact we're finding, then catch the link ----

    data class PendingLink(val contactId: Int, val rawId: Int, val contactName: String, val networkKey: String)

    private const val LINK_TIMEOUT_MS = 15 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE)

    fun setPendingLink(context: Context, link: PendingLink) {
        prefs(context).edit()
            .putInt("link_contact", link.contactId)
            .putInt("link_raw", link.rawId)
            .putString("link_name", link.contactName)
            .putString("link_network", link.networkKey)
            .putLong("link_time", System.currentTimeMillis())
            .apply()
    }

    fun getPendingLink(context: Context): PendingLink? {
        val p = prefs(context)
        if (System.currentTimeMillis() - p.getLong("link_time", 0) > LINK_TIMEOUT_MS) return null
        val contactId = p.getInt("link_contact", -1)
        if (contactId < 0) return null
        return PendingLink(contactId, p.getInt("link_raw", 0), p.getString("link_name", "") ?: "", p.getString("link_network", "") ?: "")
    }

    // ---- one-tap Messenger calls: open the chat, then Link helper taps the call button ----

    enum class AutoTap { VOICE, VIDEO }

    fun setAutoTap(context: Context, pkg: String, tap: AutoTap) {
        prefs(context).edit().putString("autotap_pkg", pkg).putString("autotap_kind", tap.name)
            .putLong("autotap_time", System.currentTimeMillis()).apply()
    }

    /** The call button waiting to be tapped in [pkg], if asked for in the last 25 seconds. */
    fun getAutoTap(context: Context, pkg: String): AutoTap? {
        val p = prefs(context)
        if (p.getString("autotap_pkg", null) != pkg) return null
        if (System.currentTimeMillis() - p.getLong("autotap_time", 0) > 25_000) return null
        return try {
            AutoTap.valueOf(p.getString("autotap_kind", "") ?: "")
        } catch (e: Exception) {
            null
        }
    }

    fun clearAutoTap(context: Context) = prefs(context).edit().remove("autotap_time").apply()

    fun clearPendingLink(context: Context) = prefs(context).edit().remove("link_time").remove("link_contact").apply()

    /** First web link in a piece of text (a share or the clipboard). */
    fun firstUrl(text: CharSequence?): String? =
        text?.let { Regex("https?://[^\\s\"'<>]+").find(it)?.value?.trimEnd('.', ',', ')') }

    fun networkFor(url: String) = networks.firstOrNull { it.matches(url) }

    fun network(key: String) = networks.first { it.key == key }

    /** A real profile address somewhere in some text (screen text or a screenshot), not a share/login link. */
    fun profileUrlIn(text: String, preferKey: String? = null): String? {
        val pattern = Regex("(?:https?://)?(?:www\\.|m\\.)?([a-z0-9.-]+\\.[a-z]{2,})/[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
        val candidates = pattern.findAll(text).mapNotNull { match ->
            val url = match.value.trimEnd('.', ',', ')').let { if (it.startsWith("http", true)) it else "https://$it" }
            val network = networkFor(url) ?: return@mapNotNull null
            if (network.usernameFrom(url).isEmpty()) null else network to url
        }.toList()
        return (candidates.firstOrNull { it.first.key == preferKey } ?: candidates.firstOrNull())?.second
    }

    /**
     * Facebook "Copy link" gives facebook.com/share/abc, which hides the profile address.
     * Follow it like a browser would to get facebook.com/username or profile.php?id=123.
     * Runs network requests: call on a background thread. Returns null if it can't tell.
     */
    fun resolveFacebook(url: String): String? {
        val facebook = network("facebook")
        if (facebook.usernameFrom(url).isNotEmpty()) return url

        fun profileFrom(candidate: String?): String? {
            if (candidate.isNullOrEmpty()) return null
            val decoded = android.net.Uri.decode(candidate).replace("\\/", "/").replace("&amp;", "&")
            Regex("fb://profile/(\\d+)").find(decoded)?.let { return "https://facebook.com/profile.php?id=${it.groupValues[1]}" }
            val next = Regex("[?&]next=([^&]+)").find(decoded)?.let { android.net.Uri.decode(it.groupValues[1]) } ?: decoded
            if (!facebook.matches(next) || next.contains("/login") || next.contains("/share/")) return null
            Regex("[?&]id=(\\d+)").find(next)?.let { return "https://facebook.com/profile.php?id=${it.groupValues[1]}" }
            val handle = facebook.usernameFrom(next)
            return if (handle.isEmpty()) null else facebook.urlFor(handle)
        }

        var current = url
        repeat(8) {
            val connection = try {
                (java.net.URL(current).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
                    )
                    setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
                }
            } catch (e: Exception) {
                return null
            }
            try {
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    val absolute = if (location.startsWith("/")) "https://www.facebook.com$location" else location
                    profileFrom(absolute)?.let { return it }
                    current = absolute
                    return@repeat
                }
                // reached a page: look for the profile address in its tags
                val html = connection.inputStream.use { stream ->
                    val bytes = ByteArray(512 * 1024)
                    var total = 0
                    while (total < bytes.size) {
                        val read = stream.read(bytes, total, bytes.size - total)
                        if (read <= 0) break
                        total += read
                    }
                    String(bytes, 0, total)
                }
                listOf(
                    Regex("fb://profile/\\d+"),
                    Regex("property=\"og:url\"\\s+content=\"([^\"]+)\""),
                    Regex("content=\"([^\"]+)\"\\s+property=\"og:url\""),
                    Regex("rel=\"canonical\"\\s+href=\"([^\"]+)\""),
                    Regex("\"userID\":\"(\\d+)\""),
                ).forEach { regex ->
                    val match = regex.find(html) ?: return@forEach
                    val value = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: match.value
                    val found = if (value.all { it.isDigit() }) "https://facebook.com/profile.php?id=$value" else profileFrom(value)
                    if (found != null) return found
                }
                return null
            } catch (e: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    /** Messenger link for a Facebook profile link, or null when the username can't be worked out. */
    fun messengerFromFacebook(facebookUrl: String): String? {
        val handle = network("facebook").usernameFrom(facebookUrl)
        return if (handle.isEmpty()) null else network("messenger").urlFor(handle)
    }

    /** A chat-app entry found on the contact: which app, which action, and the data row that opens it. */
    data class FoundChat(val app: ChatApp, val action: ChatAction, val dataId: Long)

    /** A saved social profile link on the contact. */
    data class FoundSocial(val network: Network, val url: String, val dataId: Long)

    fun findChats(context: Context, contactId: Int): List<FoundChat> {
        val byMime = chatApps.flatMap { app -> app.actions.map { it.mimeType to (app to it) } }.toMap()
        val found = ArrayList<FoundChat>()
        try {
            val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN (${byMime.keys.joinToString { "?" }})"
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE),
                selection,
                arrayOf(contactId.toString()) + byMime.keys.toTypedArray(),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val (app, action) = byMime[cursor.getString(1)] ?: continue
                    if (found.none { it.action.mimeType == action.mimeType }) {
                        found.add(FoundChat(app, action, cursor.getLong(0)))
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return found
    }

    fun findSocials(context: Context, contactId: Int): List<FoundSocial> {
        val found = ArrayList<FoundSocial>()
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, Website.URL),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), Website.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val url = cursor.getString(1)?.trim() ?: continue
                    val network = networks.firstOrNull { it.matches(url) } ?: continue
                    if (found.none { it.url.equals(url, true) }) {
                        found.add(FoundSocial(network, url, cursor.getLong(0)))
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return found.sortedBy { networks.indexOf(it.network) }
    }

    /** Discord opens a profile only from its number ID: accept the ID or a discord.com/users/<id> link. */
    fun discordUrl(input: String): String? {
        val text = input.trim()
        val id = when {
            text.matches(Regex("\\d{15,21}")) -> text
            text.contains("discord", true) -> Regex("users/(\\d{15,21})").find(text)?.groupValues?.get(1)
            else -> null
        } ?: return null
        return "https://discord.com/users/$id"
    }

    fun discordId(url: String) = Regex("users/(\\d+)").find(url)?.groupValues?.get(1)

    /** Turns whatever the user typed (username, @username or a pasted link) into a profile link. */
    fun buildUrl(network: Network, input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (text.contains("://") || network.matches(text)) {
            return if (text.contains("://")) text else "https://$text"
        }
        val handle = text.removePrefix("@").trim().replace(" ", "")
        return if (handle.isEmpty()) null else network.urlFor(handle)
    }

    /** Adds one data row (email, website, …) to the contact's first raw contact. */
    fun addData(context: Context, contactId: Int, mimeType: String, vararg values: Pair<String, Any>): Boolean =
        com.omnioone.commons.helpers.OmnioContactData.add(context, contactId.toLong(), mimeType, *values)

    /** Saves a profile link on the contact. Returns true when it worked. */
    fun addSocial(context: Context, contactId: Int, url: String): Boolean {
        val ok = com.omnioone.commons.helpers.OmnioContactData.add(
            context, contactId.toLong(), Website.CONTENT_ITEM_TYPE,
            Website.URL to url,
            Website.TYPE to Website.TYPE_PROFILE
        )
        if (ok) clearPendingLink(context)
        return ok
    }

    fun removeSocial(context: Context, dataId: Long): Boolean = com.omnioone.commons.helpers.OmnioContactData.remove(context, dataId)
}
