package com.omnioone.app.messaging

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.omnioone.app.R
import com.omnioone.app.activities.OmnioMessagesSwitch
import com.omnioone.app.activities.SimpleActivity
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.helpers.OmnioPeople
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.commons.extensions.baseConfig
import com.omnioone.app.extensions.config
import com.omnioone.commons.extensions.toast
import com.omnioone.commons.helpers.OmnioColors
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.messages.helpers.THREAD_ATTACHMENT_URIS
import com.omnioone.messages.helpers.THREAD_ID
import com.omnioone.messages.helpers.THREAD_NUMBER
import com.omnioone.messages.helpers.THREAD_TEXT
import com.omnioone.messages.helpers.THREAD_TITLE

/**
 * Omnio One new message screen (Messages rewrite, stage 6): pick someone (or type a number),
 * or switch to group mode to pick several. Also opens when another app shares something
 * or taps an "sms:" link.
 */
class OmnioNewMessageActivity : SimpleActivity() {

    private data class Pick(val name: String, val number: String, val photo: String)

    private sealed class Row {
        object Suggestions : Row()
        object GroupToggle : Row()
        data class Typed(val number: String) : Row()
        data class Header(val text: String) : Row()
        data class Contact(val person: OmnioPeople.Person) : Row()
    }

    private var people = listOf<OmnioPeople.Person>()
    private var recent = listOf<Pick>()
    private val picked = ArrayList<Pick>()
    private var groupMode = false
    private var sharedText: String? = null
    private val sharedUris = ArrayList<Uri>()

    private lateinit var search: EditText
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var startButton: TextView
    private lateinit var titleView: TextView
    private val adapter = Adapter()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readSharedContent()
        // an "sms:07…" link names who it's for: go straight to that chat
        val linked = numbersFromLink(intent.data)
        if (linked.isNotEmpty()) {
            open(linked.map { Pick(it, it, "") })
            finish()
            return
        }
        setContentView(buildScreen())
        load()
    }

    // ---- shared content and links ----

    private fun readSharedContent() {
        sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("sms_body")
        when (intent.action) {
            Intent.ACTION_SEND -> streamExtra()?.let { sharedUris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> sharedUris.addAll(streamListExtra())
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

    private fun numbersFromLink(uri: Uri?): List<String> {
        if (uri == null || uri.scheme?.lowercase() !in setOf("sms", "smsto", "mms", "mmsto")) return emptyList()
        uri.getQueryParameter("body")?.let { if (sharedText.isNullOrEmpty()) sharedText = it }
        return Uri.decode(uri.schemeSpecificPart.substringBefore("?"))
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    // ---- loading ----

    private fun load() {
        ensureBackgroundThread {
            val loaded = OmnioPeople.sort(OmnioPeople.load(this, baseConfig.ignoredContactSources.toSet()), config.sorting)
                .filter { it.numbers.isNotEmpty() }
            val chats = OmnioMessageStore.conversations(this)
                .filter { !it.isGroup && !it.isArchived && it.people.isNotEmpty() }
                .sortedByDescending { it.date }
            // one bubble per person, even if they text from more than one number format
            val seen = HashSet<String>()
            val suggestions = chats.mapNotNull { chat ->
                val person = chat.people.first()
                val key = person.contactId?.let { "c$it" } ?: person.address.filter(Char::isDigit).takeLast(9)
                if (seen.add(key)) Pick(person.label, person.address, person.photo) else null
            }.take(12)
            runOnUiThread {
                people = loaded
                recent = suggestions
                refresh()
            }
        }
    }

    private fun refresh() {
        val query = search.text.toString().trim()
        val rows = ArrayList<Row>()
        if (query.isEmpty() && recent.isNotEmpty()) rows += Row.Suggestions
        if (query.isEmpty()) rows += Row.GroupToggle
        val digits = query.filter { it.isDigit() }
        if (digits.length >= 3 && query.all { it.isDigit() || it in "+ -()" }) rows += Row.Typed(query)
        val matches = people.filter { OmnioPeople.matches(it, query) }
        if (matches.isNotEmpty()) rows += Row.Header(if (query.isEmpty()) getString(R.string.omnio_new_all_contacts) else getString(R.string.omnio_new_matches, matches.size))
        matches.forEach { rows += Row.Contact(it) }
        adapter.submit(rows)
        titleView.text = getString(if (groupMode) R.string.omnio_new_group_title else R.string.omnio_new_title)
        showChips()
    }

    // ---- picking ----

    private fun choose(pick: Pick) {
        if (!groupMode) {
            open(listOf(pick))
            return
        }
        val existing = picked.firstOrNull { same(it.number, pick.number) }
        if (existing != null) picked.remove(existing) else picked.add(pick)
        search.setText("")
        refresh()
    }

    private fun chooseContact(person: OmnioPeople.Person) {
        if (person.numbers.size == 1) {
            choose(Pick(person.name, person.numbers.first().value, person.photo))
            return
        }
        OmnioSheet.list(this, person.name, getString(R.string.omnio_new_which_number), person.numbers.map {
            OmnioSheet.Item(it.value, trailing = it.label.ifBlank { null })
        }) { index -> choose(Pick(person.name, person.numbers[index].value, person.photo)) }
    }

    private fun same(a: String, b: String): Boolean {
        val x = a.filter(Char::isDigit).takeLast(9)
        val y = b.filter(Char::isDigit).takeLast(9)
        return if (x.isNotEmpty() && y.isNotEmpty()) x == y else a == b
    }

    private fun isPicked(number: String) = picked.any { same(it.number, number) }

    /** Opens the chat with these people (creating it if it's new). */
    private fun open(who: List<Pick>) {
        if (who.isEmpty()) return
        val numbers = who.map { it.number }
        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(this, numbers.toSet())
        } catch (e: Exception) {
            toast(R.string.omnio_thread_send_failed)
            return
        }
        val screen = if (OmnioMessagesSwitch.threadOn(this)) OmnioThreadActivity::class.java
        else com.omnioone.messages.activities.ThreadActivity::class.java
        startActivity(Intent(this, screen).apply {
            putExtra(THREAD_ID, threadId)
            putExtra(THREAD_TITLE, who.joinToString(", ") { if (who.size > 1) it.name.substringBefore(" ") else it.name })
            putExtra(THREAD_NUMBER, numbers.joinToString(";"))
            putStringArrayListExtra(OmnioThreadActivity.EXTRA_ADDRESSES, ArrayList(numbers))
            sharedText?.let { putExtra(THREAD_TEXT, it) }
            if (sharedUris.isNotEmpty()) {
                putParcelableArrayListExtra(THREAD_ATTACHMENT_URIS, ArrayList(sharedUris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        })
        finish()
    }

    // ---- screen ----

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OmnioColors.BACKGROUND)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(16), dp(6))
            addView(ImageView(this@OmnioNewMessageActivity).apply {
                setImageResource(com.omnioone.commons.R.drawable.ic_arrow_left_vector)
                imageTintList = ColorStateList.valueOf(OmnioColors.TEXT)
                setPadding(dp(11), dp(11), dp(11), dp(11))
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                setOnClickListener {
                    if (groupMode) {
                        groupMode = false
                        picked.clear()
                        refresh()
                    } else finish()
                }
            })
            titleView = TextView(this@OmnioNewMessageActivity).apply {
                setTextColor(OmnioColors.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
            }
            addView(titleView)
        })

        // who it's going to (group mode)
        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(chips)
        }
        root.addView(chipScroll)

        search = EditText(this).apply {
            hint = getString(R.string.omnio_new_search)
            setTextColor(OmnioColors.TEXT)
            setHintTextColor(OmnioColors.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(OmnioColors.CARD)
            }
            setPadding(dp(18), dp(12), dp(18), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
            setOnEditorActionListener { _, _, _ ->
                val typed = text.toString().trim()
                if (typed.any { it.isDigit() }) choose(Pick(typed, typed, ""))
                true
            }
        }
        root.addView(search)

        root.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@OmnioNewMessageActivity)
            adapter = this@OmnioNewMessageActivity.adapter
            itemAnimator = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        startButton = TextView(this).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setTextColor(OmnioColors.ON_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(OmnioColors.MESSAGES)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), dp(6), dp(16), dp(10)) }
            setOnClickListener { open(picked.toList()) }
        }
        root.addView(startButton)
        return root
    }

    private fun showChips() {
        chips.removeAllViews()
        chipScroll.visibility = if (groupMode && picked.isNotEmpty()) View.VISIBLE else View.GONE
        picked.forEach { pick ->
            chips.addView(TextView(this).apply {
                text = "${pick.name}  ✕"
                setTextColor(OmnioColors.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(OmnioColors.CARD_SELECTED)
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                setOnClickListener {
                    picked.remove(pick)
                    refresh()
                }
            })
        }
        startButton.visibility = if (groupMode && picked.isNotEmpty()) View.VISIBLE else View.GONE
        startButton.text = if (picked.size == 1) getString(R.string.omnio_new_start_chat) else getString(R.string.omnio_new_start_group, picked.size)
    }

    private fun avatar(photo: String, name: String, size: Int) = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        OmnioDisplay.loadAvatar(this, photo, name, dp(size / 2))
    }

    // ---- list ----

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows = listOf<Row>()

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            Row.Suggestions -> 0
            Row.GroupToggle -> 1
            is Row.Typed -> 2
            is Row.Header -> 3
            is Row.Contact -> 4
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = if (viewType == 0) HorizontalScrollView(parent.context).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(10), dp(4), dp(10), dp(8))
                })
            } else LinearLayout(parent.context)
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                Row.Suggestions -> bindSuggestions((holder.itemView as HorizontalScrollView).getChildAt(0) as LinearLayout)
                Row.GroupToggle -> bindAction(holder.itemView as LinearLayout, if (groupMode) "✓" else "👥", getString(if (groupMode) R.string.omnio_new_group_on else R.string.omnio_new_group), null) {
                    groupMode = !groupMode
                    if (!groupMode) picked.clear()
                    refresh()
                }
                is Row.Typed -> bindAction(holder.itemView as LinearLayout, "💬", getString(R.string.omnio_new_send_to, row.number), null) {
                    choose(Pick(row.number, row.number, ""))
                }
                is Row.Header -> (holder.itemView as LinearLayout).apply {
                    removeAllViews()
                    setPadding(dp(20), dp(14), dp(20), dp(6))
                    addView(TextView(context).apply {
                        text = row.text
                        setTextColor(OmnioColors.MESSAGES_SOFT)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTypeface(typeface, Typeface.BOLD)
                    })
                }
                is Row.Contact -> bindContact(holder.itemView as LinearLayout, row.person)
            }
        }

        private fun bindSuggestions(strip: LinearLayout) {
            strip.removeAllViews()
            recent.forEach { pick ->
                strip.addView(LinearLayout(this@OmnioNewMessageActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    layoutParams = LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(avatar(pick.photo, pick.name, 58).apply {
                        if (isPicked(pick.number)) foreground = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setStroke(dp(3), OmnioColors.MESSAGES)
                        }
                    })
                    addView(TextView(this@OmnioNewMessageActivity).apply {
                        text = pick.name.substringBefore(" ")
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(OmnioColors.TEXT)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(0, dp(6), 0, 0)
                    })
                    setOnClickListener { choose(pick) }
                })
            }
        }

        private fun bindAction(row: LinearLayout, emoji: String, label: String, sub: String?, onClick: () -> Unit) = row.apply {
            removeAllViews()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(TextView(context).apply {
                text = emoji
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(OmnioColors.CARD)
                }
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(14) }
            })
            addView(TextView(context).apply {
                text = label
                setTextColor(OmnioColors.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            setOnClickListener { onClick() }
        }

        private fun bindContact(row: LinearLayout, person: OmnioPeople.Person) = row.apply {
            removeAllViews()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            val selected = person.numbers.any { isPicked(it.value) }
            addView(avatar(person.photo, person.name, 46).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
                foreground = if (selected) GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(3), OmnioColors.MESSAGES)
                } else null
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = person.name
                    setTextColor(OmnioColors.TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = if (person.numbers.size > 1) getString(R.string.omnio_new_numbers, person.mainNumber, person.numbers.size - 1) else person.mainNumber
                    setTextColor(OmnioColors.TEXT_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                })
            })
            if (selected) addView(TextView(context).apply {
                text = "✓"
                setTextColor(OmnioColors.MESSAGES)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            setOnClickListener { chooseContact(person) }
        }
    }
}
