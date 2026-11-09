package com.omnioone.app.activities

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.omnioone.app.R
import com.omnioone.app.extensions.areMultipleSIMsAvailable
import com.omnioone.app.extensions.config
import com.omnioone.app.extensions.startCallWithConfirmationCheck
import com.omnioone.app.helpers.OmnioCallLog
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.commons.extensions.isDefaultDialer
import com.omnioone.commons.extensions.launchSendSMSIntent
import com.omnioone.commons.extensions.toast
import com.omnioone.commons.helpers.OmnioColors
import com.omnioone.commons.helpers.OmnioContactData
import com.omnioone.commons.helpers.ensureBackgroundThread
import java.text.Normalizer
import java.util.Locale

/**
 * Omnio One keypad: type a number or a name (like T9), see matching contacts and recent
 * numbers as you go, tap to call. Long-press 0 for +, long-press 1–9 for speed dial,
 * press call with nothing typed to bring back the last number.
 */
class DialpadActivity : SimpleActivity() {

    private data class Person(
        val contactId: Long,
        val name: String,
        val number: String,
        val digits: String,
        val photo: String,
        val label: String,
        val nameKeys: String,
        val wordStarts: List<Int>,
    )

    private data class Suggestion(val person: Person?, val number: String, val title: String, val subtitle: String, val highlight: IntRange?)

    private val input = StringBuilder()
    private lateinit var numberView: TextView
    private lateinit var deleteButton: ImageView
    private lateinit var actionRow: LinearLayout
    private lateinit var suggestionsHeader: TextView
    private val suggestions = SuggestionsAdapter()
    private var people = listOf<Person>()
    private var recentNumbers = listOf<Pair<String, OmnioCallLog.Person?>>()
    private var lastCalled: String? = null

    private var tones: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        handleDialIntent(intent)
        render()
        load()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDialIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (config.dialpadBeeps && tones == null) {
            tones = try {
                ToneGenerator(AudioManager.STREAM_DTMF, 80)
            } catch (e: Exception) {
                null
            }
        }
        render()
    }

    override fun onPause() {
        tones?.release()
        tones = null
        super.onPause()
    }

    private fun handleDialIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        if ((intent.action == Intent.ACTION_DIAL || intent.action == Intent.ACTION_VIEW) && data.startsWith("tel:")) {
            input.clear()
            input.append(Uri.decode(data).substringAfter("tel:").filter { it.isDigit() || it in "+*#," })
        }
    }

    // ---- loading contacts and recent numbers ----

    private fun load() {
        ensureBackgroundThread {
            val loaded = loadPeople()
            val recent = OmnioCallLog.load(this, limit = 80, group = false)
                .filter { it.number.isNotEmpty() }
                .distinctBy { it.number.filter { char -> char.isDigit() }.takeLast(9) }
                .take(8)
                .map { it.number to it.person }
            val last = OmnioCallLog.load(this, limit = 30, group = false).firstOrNull { it.latest.isOutgoing && it.number.isNotEmpty() }?.number
            runOnUiThread {
                people = loaded
                recentNumbers = recent
                lastCalled = last
                render()
            }
        }
    }

    private fun loadPeople(): List<Person> {
        val list = ArrayList<Person>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                ),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1).orEmpty()
                    val number = cursor.getString(2).orEmpty()
                    if (number.isEmpty()) continue
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, cursor.getInt(4), cursor.getString(5).orEmpty()).toString()
                    val (keys, starts) = keypadKeys(name)
                    list += Person(cursor.getLong(0), name, number, number.filter { it.isDigit() || it == '+' }, cursor.getString(3).orEmpty(), label, keys, starts)
                }
            }
        } catch (ignored: Exception) {
        }
        return list.distinctBy { it.contactId to it.digits.takeLast(9) }
    }

    /** A name turned into keypad digits ("Jo Ann" -> "56266"), plus where each word starts. */
    private fun keypadKeys(name: String): Pair<String, List<Int>> {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace("\\p{M}".toRegex(), "").lowercase(Locale.ROOT)
        val keys = StringBuilder()
        val starts = ArrayList<Int>()
        var newWord = true
        plain.forEach { char ->
            val key = keyFor(char)
            if (key == null) {
                newWord = true
            } else {
                if (newWord) starts += keys.length
                keys.append(key)
                newWord = false
            }
        }
        return keys.toString() to starts
    }

    private fun keyFor(char: Char): Char? = when (char) {
        in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'; in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'; in 'p'..'s' -> '7'; in 't'..'v' -> '8'; in 'w'..'z' -> '9'
        in '0'..'9' -> char
        else -> null
    }

    // ---- what the screen shows ----

    private fun render() {
        if (!::numberView.isInitialized) return
        val typed = input.toString()
        numberView.text = if (typed.isEmpty()) "" else formatted(typed)
        numberView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (typed.length > 13) 26f else 34f)
        deleteButton.visibility = if (typed.isEmpty()) View.INVISIBLE else View.VISIBLE
        buildActions(typed)

        if (typed.isEmpty()) {
            suggestionsHeader.text = getString(R.string.omnio_dialpad_recent)
            suggestionsHeader.visibility = if (recentNumbers.isEmpty()) View.GONE else View.VISIBLE
            suggestions.submit(recentNumbers.map { (number, person) ->
                val match = person?.let { found -> people.firstOrNull { it.contactId == found.contactId } }
                Suggestion(match, number, person?.name ?: number, if (person != null) "${person.label}  ·  $number" else "", null)
            })
        } else {
            val found = search(typed)
            suggestionsHeader.text = getString(R.string.omnio_dialpad_matches, found.size)
            suggestionsHeader.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
            suggestions.submit(found)
        }
    }

    private fun formatted(typed: String): String {
        if (typed.any { it == '*' || it == '#' || it == ',' }) return typed
        return PhoneNumberUtils.formatNumber(typed, Locale.getDefault().country) ?: typed
    }

    private fun search(typed: String): List<Suggestion> {
        val digits = typed.filter { it.isDigit() }
        if (digits.isEmpty()) return emptyList()
        data class Scored(val suggestion: Suggestion, val score: Int)
        val scored = people.mapNotNull { person ->
            val numberIndex = person.digits.filter { it.isDigit() }.indexOf(digits)
            val nameIndex = person.nameKeys.indexOf(digits)
            val subtitle = "${person.label}  ·  ${person.number}"
            when {
                nameIndex >= 0 && nameIndex in person.wordStarts ->
                    Scored(Suggestion(person, person.number, person.name, subtitle, nameRange(person, nameIndex, digits.length)), if (nameIndex == 0) 0 else 1)
                numberIndex >= 0 -> Scored(Suggestion(person, person.number, person.name, subtitle, null), if (numberIndex == 0) 2 else 3)
                nameIndex >= 0 -> Scored(Suggestion(person, person.number, person.name, subtitle, nameRange(person, nameIndex, digits.length)), 4)
                else -> null
            }
        }
        return scored.sortedWith(compareBy<Scored> { it.score }.thenBy { it.suggestion.title.lowercase() }).take(40).map { it.suggestion }
    }

    /** Which letters of the name match the keys typed, so they can be highlighted. */
    private fun nameRange(person: Person, keyStart: Int, length: Int): IntRange? {
        val plain = Normalizer.normalize(person.name, Normalizer.Form.NFD).replace("\\p{M}".toRegex(), "")
        if (plain.length != person.name.length) return null
        var keyIndex = 0
        var start = -1
        plain.forEachIndexed { index, char ->
            if (keyFor(char.lowercaseChar()) != null) {
                if (keyIndex == keyStart) start = index
                if (keyIndex == keyStart + length - 1 && start >= 0) return start..index
                keyIndex++
            }
        }
        return null
    }

    /** Shortcuts under the number: add to contacts, text it, paste. */
    private fun buildActions(typed: String) {
        actionRow.removeAllViews()
        if (typed.isEmpty()) {
            if (clipboardHasText()) actionRow.addView(chip(getString(R.string.omnio_dialpad_paste), OmnioColors.CALLS) { paste() })
            return
        }
        val digits = typed.filter { it.isDigit() }
        val known = people.any { PhoneNumberUtils.compare(it.number, typed) || (digits.length >= 7 && it.digits.endsWith(digits.takeLast(9))) }
        if (!known && digits.length >= 3) {
            actionRow.addView(chip(getString(R.string.omnio_add_to_contacts), OmnioColors.CONTACTS) {
                startActivity(Intent(this, OmnioEditContactActivity::class.java).putExtra(OmnioEditContactActivity.EXTRA_NUMBER, typed))
            })
        }
        if (digits.length >= 3) {
            actionRow.addView(chip(getString(R.string.omnio_text), OmnioColors.MESSAGES) { launchSendSMSIntent(typed) })
        }
    }

    private fun clipboardHasText(): Boolean = try {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/*") == true
    } catch (e: Exception) {
        false
    }

    private fun paste() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val number = text.filter { it.isDigit() || it in "+*#" }
        if (number.isEmpty()) {
            toast(R.string.omnio_dialpad_no_number)
            return
        }
        input.clear()
        input.append(number)
        render()
    }

    // ---- typing ----

    private fun press(char: Char, view: View?) {
        input.append(char)
        if (config.dialpadVibration) view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        checkSecretCode()
        render()
    }

    private fun deleteOne(view: View?) {
        if (input.isNotEmpty()) input.deleteCharAt(input.length - 1)
        if (config.dialpadVibration) view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        render()
    }

    private fun clearAll() {
        input.clear()
        render()
    }

    /** *#*#code#*#* opens hidden phone menus, like on any Android dialer. */
    private fun checkSecretCode() {
        val typed = input.toString()
        if (typed.length > 8 && typed.startsWith("*#*#") && typed.endsWith("#*#*")) {
            val code = typed.substring(4, typed.length - 4)
            if (isDefaultDialer()) {
                try {
                    getSystemService(TelephonyManager::class.java)?.sendDialerSpecialCode(code)
                } catch (ignored: Exception) {
                }
            } else {
                toast(R.string.omnio_dialpad_needs_default)
            }
        }
    }

    private fun tone(char: Char, on: Boolean) {
        val generator = tones ?: return
        if (!on) {
            generator.stopTone()
            return
        }
        val type = when (char) {
            '0' -> ToneGenerator.TONE_DTMF_0; '1' -> ToneGenerator.TONE_DTMF_1; '2' -> ToneGenerator.TONE_DTMF_2
            '3' -> ToneGenerator.TONE_DTMF_3; '4' -> ToneGenerator.TONE_DTMF_4; '5' -> ToneGenerator.TONE_DTMF_5
            '6' -> ToneGenerator.TONE_DTMF_6; '7' -> ToneGenerator.TONE_DTMF_7; '8' -> ToneGenerator.TONE_DTMF_8
            '9' -> ToneGenerator.TONE_DTMF_9; '*' -> ToneGenerator.TONE_DTMF_S; '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        generator.startTone(type, 150)
    }

    private fun longPress(char: Char, view: View) {
        when {
            char == '0' -> {
                if (input.isNotEmpty()) input.deleteCharAt(input.length - 1)
                press('+', view)
            }
            char in '1'..'9' && input.length == 1 -> {
                val speed = config.getSpeedDialValues().firstOrNull { it.id == char.digitToInt() }
                if (speed?.isValid() == true) {
                    input.clear()
                    render()
                    call(speed.number, speed.getName(this))
                } else if (char == '1') {
                    input.clear()
                    render()
                    callVoicemail()
                }
            }
        }
    }

    // ---- calling ----

    private fun call(number: String, name: String? = null) {
        startCallWithConfirmationCheck(number, name ?: number)
        handler.postDelayed({ clearAll() }, 1000)
    }

    /** Hold 1: rings your voicemail (unless you've put someone on speed dial 1). */
    @SuppressLint("MissingPermission")
    private fun callVoicemail() {
        try {
            getSystemService(android.telecom.TelecomManager::class.java)
                .placeCall(Uri.fromParts("voicemail", "", null), Bundle())
        } catch (e: Exception) {
            toast(R.string.omnio_dialpad_no_voicemail)
        }
    }

    private fun callTyped() {
        val typed = input.toString()
        if (typed.isEmpty()) {
            val last = lastCalled ?: recentNumbers.firstOrNull()?.first
            if (last == null) {
                toast(R.string.omnio_dialpad_nothing_to_redial)
            } else {
                input.append(last)
                render()
            }
            return
        }
        call(typed, people.firstOrNull { PhoneNumberUtils.compare(it.number, typed) }?.name)
    }

    private fun callWithSimChoice(): Boolean {
        val typed = input.toString()
        if (typed.isEmpty() || !areMultipleSIMsAvailable()) return false
        startCallWithConfirmationCheck(recipient = typed, name = typed, forceSimSelector = true)
        return true
    }

    private fun openContact(person: Person) {
        ensureBackgroundThread {
            val rawId = OmnioContactData.firstRawId(this, person.contactId) ?: return@ensureBackgroundThread
            runOnUiThread {
                startActivity(Intent(this, OmnioContactActivity::class.java).putExtra(OmnioContactActivity.EXTRA_CONTACT_ID, rawId.toInt()))
            }
        }
    }

    // ---- building the screen ----

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OmnioColors.BACKGROUND)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(16), 0)
            addView(ImageView(this@DialpadActivity).apply {
                setImageResource(com.omnioone.commons.R.drawable.ic_arrow_left_vector)
                imageTintList = ColorStateList.valueOf(OmnioColors.TEXT)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                background = getDrawable(android.R.drawable.list_selector_background)
                contentDescription = getString(com.omnioone.commons.R.string.back)
                setOnClickListener { finish() }
            })
            suggestionsHeader = TextView(this@DialpadActivity).apply {
                setTextColor(OmnioColors.TEXT_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.05f
                setPadding(dp(6), 0, 0, 0)
            }
            addView(suggestionsHeader)
        })

        root.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DialpadActivity)
            adapter = suggestions
            itemAnimator = null
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        // number + delete
        root.addView(FrameLayout(this).apply {
            setPadding(dp(56), dp(6), dp(8), 0)
            numberView = TextView(this@DialpadActivity).apply {
                setTextColor(OmnioColors.TEXT)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.START
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { marginEnd = dp(48) }
                setOnLongClickListener {
                    if (clipboardHasText()) paste()
                    true
                }
            }
            addView(numberView)
            deleteButton = ImageView(this@DialpadActivity).apply {
                setImageResource(R.drawable.omnio_ic_backspace)
                imageTintList = ColorStateList.valueOf(OmnioColors.TEXT_MUTED)
                setPadding(dp(12), dp(14), dp(12), dp(14))
                layoutParams = FrameLayout.LayoutParams(dp(52), dp(56), Gravity.END or Gravity.CENTER_VERTICAL)
                background = getDrawable(android.R.drawable.list_selector_background)
                contentDescription = getString(R.string.omnio_dialpad_delete)
                setOnClickListener { deleteOne(it) }
                setOnLongClickListener { clearAll(); true }
            }
            addView(deleteButton)
        })

        actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(40)
        }
        root.addView(actionRow)

        // keys
        val keys = listOf(
            listOf('1' to "", '2' to "ABC", '3' to "DEF"),
            listOf('4' to "GHI", '5' to "JKL", '6' to "MNO"),
            listOf('7' to "PQRS", '8' to "TUV", '9' to "WXYZ"),
            listOf('*' to "", '0' to "+", '#' to ""),
        )
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(4), dp(28), 0)
            clipChildren = false
        }
        keys.forEach { rowKeys ->
            pad.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                rowKeys.forEach { (char, letters) ->
                    addView(FrameLayout(this@DialpadActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(78), 1f)
                        clipChildren = false
                        val ring = com.omnioone.app.helpers.OrbitRing(this@DialpadActivity, dp(3).toFloat()).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER)
                            alpha = 0f
                        }
                        addView(key(char, letters, ring).apply { layoutParams = FrameLayout.LayoutParams(dp(70), dp(70), Gravity.CENTER) })
                        addView(ring)
                    })
                }
            })
        }
        root.addView(pad)

        // call button
        root.addView(FrameLayout(this).apply {
            setPadding(0, dp(8), 0, dp(14))
            addView(ImageView(this@DialpadActivity).apply {
                setImageResource(R.drawable.ic_call_accept)
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                setPadding(dp(20), dp(20), dp(20), dp(20))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(OmnioColors.MESSAGES)
                }
                layoutParams = FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
                contentDescription = getString(R.string.omnio_call)
                setOnClickListener { callTyped() }
                setOnLongClickListener { callWithSimChoice() }
            })
        })
        return root
    }

    @SuppressLint("ClickableViewAccessibility")
    /** One quick spin of the Omnio ring around a key. */
    private fun spin(ring: View) {
        ring.animate().cancel()
        ring.rotation = 0f
        ring.alpha = 1f
        ring.animate().rotation(360f).setDuration(480).setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { ring.animate().alpha(0f).setDuration(160).start() }.start()
    }

    private fun key(char: Char, letters: String, ring: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(35).toFloat()
            setColor(OmnioColors.CARD)
        }
        addView(TextView(this@DialpadActivity).apply {
            text = char.toString()
            setTextColor(OmnioColors.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (char == '*') 34f else 28f)
            gravity = Gravity.CENTER
            includeFontPadding = false
        })
        if (char == '1') addView(ImageView(this@DialpadActivity).apply {
            setImageResource(R.drawable.omnio_ic_voicemail)
            imageTintList = ColorStateList.valueOf(OmnioColors.TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        })
        if (letters.isNotEmpty()) addView(TextView(this@DialpadActivity).apply {
            text = letters
            setTextColor(OmnioColors.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        })
        contentDescription = char.toString()

        val longTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val longPressRunnable = Runnable { longPress(char, this) }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start()
                    (background as GradientDrawable).setColor(OmnioColors.CARD_SELECTED)
                    press(char, view)
                    tone(char, true)
                    spin(ring)
                    if (char.isDigit()) handler.postDelayed(longPressRunnable, longTimeout)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    (background as GradientDrawable).setColor(OmnioColors.CARD)
                    tone(char, false)
                    handler.removeCallbacks(longPressRunnable)
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                }
            }
            true
        }
    }

    private fun chip(label: String, colour: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(7), dp(16), dp(7))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor((colour and 0x00FFFFFF) or 0x26000000)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
        setOnClickListener { onClick() }
    }

    // ---- suggestions list ----

    private inner class SuggestionsAdapter : RecyclerView.Adapter<SuggestionsAdapter.Holder>() {
        private var items = listOf<Suggestion>()

        fun submit(list: List<Suggestion>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(parent.context))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            private val avatar = ImageView(row.context)
            private val title = TextView(row.context)
            private val subtitle = TextView(row.context)

            init {
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(20), dp(8), dp(20), dp(8))
                row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                row.background = getDrawable(android.R.drawable.list_selector_background)
                avatar.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                row.addView(avatar)
                row.addView(LinearLayout(row.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                    title.setTextColor(OmnioColors.TEXT)
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    title.maxLines = 1
                    title.ellipsize = TextUtils.TruncateAt.END
                    subtitle.setTextColor(OmnioColors.TEXT_MUTED)
                    subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    subtitle.maxLines = 1
                    addView(title)
                    addView(subtitle)
                })
            }

            fun bind(item: Suggestion) {
                OmnioDisplay.loadAvatar(avatar, item.person?.photo.orEmpty(), item.title, dp(21))
                val text = SpannableString(item.title)
                item.highlight?.let {
                    if (it.last < item.title.length) text.setSpan(ForegroundColorSpan(OmnioColors.CALLS), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                title.text = text
                title.setTypeface(null, Typeface.BOLD)
                subtitle.text = item.subtitle
                subtitle.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE
                row.setOnClickListener { call(item.number, item.person?.name) }
                row.setOnLongClickListener {
                    input.clear()
                    input.append(item.number.filter { char -> char.isDigit() || char in "+*#" })
                    render()
                    true
                }
                avatar.setOnClickListener { item.person?.let { openContact(it) } ?: row.performClick() }
            }
        }
    }
}
