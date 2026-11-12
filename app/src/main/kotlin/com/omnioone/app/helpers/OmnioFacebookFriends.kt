package com.omnioone.app.helpers

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Omnio One Facebook matching: reads your friends list from Facebook's "Download your information"
 * file, matches friends to your contacts by name, and remembers your decisions. Everything stays
 * on the phone; only friends' names and "friends since" dates are kept.
 */
object OmnioFacebookFriends {

    data class Friend(val name: String, val since: Long)

    data class Match(val friend: Friend, val person: OmnioPeople.Person, val score: Int, val sameName: Boolean = false) {
        /** Never "sure" when the name is shared: two contacts or two friends with it means you pick. */
        val isSure get() = score >= SURE && !sameName
    }

    data class Result(val sure: List<Match>, val possible: List<Match>, val notInContacts: Int, val alreadyLinked: Int)

    const val SURE = 90
    private const val POSSIBLE = 60
    private const val FILE = "omnio_fb_friends.json"

    private fun prefs(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE)

    // ---- importing ----

    /** Reads a Facebook download (ZIP or the friends JSON on its own). Returns how many friends were found. */
    fun import(context: Context, uri: Uri): Int {
        val friends = ArrayList<Friend>()
        val resolver = context.contentResolver
        val isZip = resolver.openInputStream(uri)?.use { stream ->
            val head = ByteArray(2)
            stream.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        } ?: false
        if (isZip) {
            resolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase()
                        if (!entry.isDirectory && name.endsWith(".json") && name.contains("friend") &&
                            !name.contains("removed") && !name.contains("request") && !name.contains("rejected") &&
                            !name.contains("suggest") && !name.contains("may_know") && !name.contains("follow")
                        ) {
                            friends += parse(zip.readBytes().toString(Charsets.UTF_8))
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        } else {
            resolver.openInputStream(uri)?.use { friends += parse(it.readBytes().toString(Charsets.UTF_8)) }
        }
        // two friends can share a name: keep both (they became friends at different times)
        val unique = friends.filter { it.name.isNotBlank() }.distinctBy { it.name to it.since }
        if (unique.isEmpty()) return 0
        val array = JSONArray()
        unique.forEach { array.put(JSONObject().put("name", it.name).put("since", it.since)) }
        File(context.filesDir, FILE).writeText(array.toString())
        prefs(context).edit().putLong("fb_imported_at", System.currentTimeMillis()).apply()
        return unique.size
    }

    /** Finds every {name, timestamp} list in a Facebook JSON file ("friends_v2" and friends). */
    private fun parse(text: String): List<Friend> {
        val found = ArrayList<Friend>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (value.has("name") && (value.has("timestamp") || value.length() <= 3)) {
                        found += Friend(fixText(value.optString("name")), value.optLong("timestamp", 0L))
                    } else {
                        value.keys().forEach { key ->
                            if (!key.contains("removed") && !key.contains("request") && !key.contains("rejected")) walk(value.opt(key))
                        }
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        try {
            walk(if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text))
        } catch (ignored: Exception) {
        }
        return found
    }

    /** Facebook writes accented letters as escaped UTF-8 bytes ("RenÃ©"); turn them back into "René". */
    private fun fixText(text: String): String {
        if (text.none { it.code in 0x80..0xFF } || text.any { it.code > 0xFF }) return text
        val fixed = String(ByteArray(text.length) { text[it].code.toByte() }, Charsets.UTF_8)
        return if (fixed.contains('�')) text else fixed
    }

    fun hasImport(context: Context) = File(context.filesDir, FILE).exists()

    fun importedAt(context: Context) = prefs(context).getLong("fb_imported_at", 0L)

    fun friends(context: Context): List<Friend> = try {
        val array = JSONArray(File(context.filesDir, FILE).readText())
        (0 until array.length()).map { array.getJSONObject(it).let { item -> Friend(item.getString("name"), item.optLong("since")) } }
    } catch (e: Exception) {
        emptyList()
    }

    fun forget(context: Context) {
        File(context.filesDir, FILE).delete()
        prefs(context).edit().remove("fb_imported_at").remove("fb_not_them").apply()
    }

    // ---- decisions ----

    private fun decisionKey(friend: Friend, person: OmnioPeople.Person) =
        if (friend.since > 0) "${friend.name}#${friend.since}|${person.contactId}" else "${friend.name}|${person.contactId}"

    fun markNotThem(context: Context, match: Match) {
        val current = prefs(context).getStringSet("fb_not_them", emptySet()).orEmpty()
        prefs(context).edit().putStringSet("fb_not_them", current + decisionKey(match.friend, match.person)).apply()
    }

    // ---- matching ----

    private val nicknameGroups = listOf(
        "alexander alex alexandra sandra sasha xander", "andrew andy drew", "anthony tony ant", "benjamin ben benny",
        "charles charlie chas chuck", "christopher chris kit", "christine christina chris tina", "daniel dan danny",
        "david dave davey", "edward ed eddie ted ned", "elizabeth liz lizzie beth betty eliza libby", "emily em emmy",
        "frederick fred freddie", "gareth gaz", "gary gaz", "harold harry hal", "henry harry hal", "isabel isabella izzy bella",
        "jacob jake", "james jim jimmy jamie jay", "jennifer jen jenny", "jessica jess jessie", "john jon johnny jack",
        "jonathan jon jonny", "joseph joe joey", "joshua josh", "katherine catherine kate katie kat cathy kathy",
        "kenneth ken kenny", "lawrence laurence larry laurie", "leonard leo len lenny", "margaret maggie meg peggy",
        "matthew matt matty", "michael mike mick mikey micky", "nathan nate nat", "nathaniel nat nate", "nicholas nick nicky",
        "nicola nicky nic", "oliver ollie olly", "patricia pat patty trish", "patrick pat paddy", "peter pete",
        "philip phillip phil pip", "rebecca becky becca", "richard rich rick ricky dick", "robert rob robbie bob bobby bert",
        "ronald ron ronnie", "samantha sam sammy", "samuel sam sammy", "stephen steven steve stevie", "susan sue susie",
        "terence terry", "thomas tom tommy", "timothy tim timmy", "victoria vicky tori", "william will bill billy liam",
        "zachary zach zack", "abigail abby abi", "alfred alf alfie", "amanda mandy", "deborah debbie deb", "dorothy dot dottie",
        "douglas doug", "gregory greg", "jacqueline jackie", "jeffrey geoffrey jeff geoff", "joanne jo joanna", "kimberly kim",
        "lucy lou", "louise lou lulu", "megan meg", "melissa mel", "melanie mel", "natalie nat", "pamela pam", "raymond ray",
        "sarah sara sal sally", "stuart stu", "theodore theo ted", "vincent vince", "wayne", "callum cal", "connor con",
        "daniella dani", "gabriel gabe", "jordan jord", "kieran kez", "mohammed muhammad mohammad mo", "oluwaseun seun",
    ).map { it.split(" ").toSet() }

    private fun sameFirstName(a: String, b: String) = a == b || nicknameGroups.any { a in it && b in it }

    private fun tokens(name: String): List<String> =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace("\\p{M}".toRegex(), "").lowercase()
            .replace("[^a-z\\s'-]".toRegex(), " ").replace("[-']".toRegex(), " ")
            .split(" ").filter { it.isNotBlank() }

    private fun close(a: String, b: String): Boolean {
        if (a.length < 4 || b.length < 4 || kotlin.math.abs(a.length - b.length) > 1) return false
        // one letter added, removed or changed
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return dp[a.length][b.length] <= 1
    }

    /** How sure we are that a Facebook friend and a contact are the same person (0–100). */
    fun score(friendName: String, contactName: String, firstNameOnlyUnique: Boolean): Int {
        val f = tokens(friendName)
        val c = tokens(contactName)
        if (f.isEmpty() || c.isEmpty()) return 0
        if (f == c) return 100
        if (f.size == c.size && f.sorted() == c.sorted()) return 95
        val fFirst = f.first()
        val fLast = f.last()
        if (c.size == 1) return if (firstNameOnlyUnique && sameFirstName(c[0], fFirst)) 60 else 0
        val cFirst = c.first()
        val cLast = c.last()
        if (fLast == cLast && sameFirstName(fFirst, cFirst)) return 85
        if (fFirst == cFirst && fLast == cLast) return 85 // a middle name or initial on one side
        if (fLast == cLast && close(fFirst, cFirst)) return 70
        if (sameFirstName(fFirst, cFirst) && close(fLast, cLast)) return 70
        if (sameFirstName(fFirst, cFirst) && f.size > 2 && cLast in f) return 65 // e.g. a maiden name kept in the middle
        return 0
    }

    /**
     * Matches friends to contacts. Each friend and each contact is used at most once, best score first;
     * contacts already linked to Facebook and pairs you said "Not them" to are left out.
     */
    fun match(context: Context, people: List<OmnioPeople.Person>, linkedToFacebook: Set<Int>): Result {
        val friends = friends(context)
        val notThem = prefs(context).getStringSet("fb_not_them", emptySet()).orEmpty()
        val open = people.filter { it.contactId !in linkedToFacebook }
        val firstNameCount = friends.groupingBy { tokens(it.name).firstOrNull().orEmpty() }.eachCount()
        val singleNameCount = open.filter { tokens(it.name).size == 1 }.groupingBy { tokens(it.name).first() }.eachCount()

        val candidates = ArrayList<Match>()
        friends.forEach { friend ->
            val first = tokens(friend.name).firstOrNull().orEmpty()
            open.forEach { person ->
                val single = tokens(person.name).singleOrNull()
                val unique = single != null && (firstNameCount[first] ?: 0) == 1 && (singleNameCount[single] ?: 0) == 1
                val score = score(friend.name, person.name, unique)
                val said = decisionKey(friend, person) in notThem || "${friend.name}|${person.contactId}" in notThem
                if (score >= POSSIBLE && !said) candidates += Match(friend, person, score)
            }
        }
        // the same name on two contacts (or two friends) can't be told apart by name alone
        val strongContactsPerName = candidates.filter { it.score >= SURE }.groupBy { it.friend.name }.mapValues { (_, list) -> list.map { it.person.contactId }.toSet().size }
        val friendsPerName = friends.groupingBy { it.name }.eachCount()
        val usedFriends = HashSet<Friend>()
        val usedContacts = HashSet<Int>()
        val chosen = ArrayList<Match>()
        candidates.sortedByDescending { it.score }.forEach { match ->
            if (match.friend !in usedFriends && match.person.contactId !in usedContacts) {
                usedFriends += match.friend
                usedContacts += match.person.contactId
                val shared = (strongContactsPerName[match.friend.name] ?: 0) > 1 || (friendsPerName[match.friend.name] ?: 0) > 1
                chosen += match.copy(sameName = shared)
            }
        }
        val linkedNames = people.filter { it.contactId in linkedToFacebook }.map { tokens(it.name) }.toSet()
        val alreadyLinked = friends.count { tokens(it.name) in linkedNames }
        return Result(
            sure = chosen.filter { it.isSure }.sortedBy { it.person.name.lowercase() },
            possible = chosen.filter { !it.isSure }.sortedByDescending { it.score },
            notInContacts = (friends.size - chosen.size - alreadyLinked).coerceAtLeast(0),
            alreadyLinked = alreadyLinked,
        )
    }

    // ---- the linking queue ----

    /** True while a run of Facebook links is in progress; links caught then come back to the matching screen. */
    fun isQueueActive(context: Context) = prefs(context).getBoolean("fb_queue_active", false)

    fun setQueueActive(context: Context, active: Boolean) = prefs(context).edit().putBoolean("fb_queue_active", active).apply()
}
