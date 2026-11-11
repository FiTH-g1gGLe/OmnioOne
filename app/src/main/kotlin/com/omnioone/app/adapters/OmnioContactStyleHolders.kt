package com.omnioone.app.adapters

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import com.omnioone.app.R
import com.omnioone.app.helpers.OmnioAppTags
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.helpers.OmnioPeople
import com.omnioone.app.helpers.OrbitRing
import com.omnioone.app.core.OmnioColors
import java.security.MessageDigest

/** What the extra contact styles need from the list. */
interface ContactStyleHost {
    val textColor: Int
    val density: Int
    fun name(person: OmnioPeople.Person): CharSequence
    fun badges(person: OmnioPeople.Person): List<OmnioAppTags.Badge>
    fun click(person: OmnioPeople.Person)
    fun photo(person: OmnioPeople.Person)
    fun longClick(person: OmnioPeople.Person, view: View)
    fun call(person: OmnioPeople.Person)
    fun text(person: OmnioPeople.Person)
}

private fun View.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
private fun View.dpf(value: Float) = value * resources.displayMetrics.density

private fun ripple(view: View) {
    val value = TypedValue()
    view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
    view.foreground = view.context.getDrawable(value.resourceId)
}

private fun View.listParams(start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) =
    RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(start, top, end, bottom) }

private fun verticalFor(view: View, density: Int, normal: Int) = when (density) {
    OmnioDisplay.DENSITY_COMPACT -> view.dp(normal / 2)
    OmnioDisplay.DENSITY_COMFY -> view.dp(normal * 3 / 2)
    else -> view.dp(normal)
}

private fun soft(colour: Int, alpha: Int = 0x26) = (colour and 0x00FFFFFF) or (alpha shl 24)

private fun roundButton(parent: View, icon: Int, colour: Int, size: Int = 40, onDark: Boolean = false) = ImageView(parent.context).apply {
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(if (onDark) 0xFFFFFFFF.toInt() else colour)
    val pad = parent.dp(size / 4)
    setPadding(pad, pad, pad, pad)
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (onDark) colour else soft(colour))
    }
    layoutParams = LinearLayout.LayoutParams(parent.dp(size), parent.dp(size)).apply { marginStart = parent.dp(8) }
}

/** Keeps a view square, whatever width its column gives it. */
private fun keepSquare(view: View, ratio: Float = 1f) {
    view.post {
        val width = view.width
        val wanted = (width * ratio).toInt()
        if (width > 0 && view.layoutParams.height != wanted) view.layoutParams = view.layoutParams.apply { height = wanted }
    }
}

private fun badgeRow(parent: LinearLayout, badges: List<OmnioAppTags.Badge>) {
    parent.removeAllViews()
    parent.visibility = if (badges.isEmpty()) View.GONE else View.VISIBLE
    val size = parent.dp(15)
    badges.forEach { badge ->
        parent.addView(ImageView(parent.context).apply {
            setImageDrawable(OmnioAppTags.icon(parent.context, badge))
            contentDescription = badge.name
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = size / 3 }
        })
    }
}

private fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it.first().isLetterOrDigit() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

// ---------------------------------------------------------------- list styles

/** Bubbles: the name sits in a speech bubble beside the photo. */
class BubbleHolder(private val row: LinearLayout, private val host: ContactStyleHost) : RecyclerView.ViewHolder(row) {
    private val avatar = ImageView(row.context)
    private val bubble = LinearLayout(row.context)
    private val nameView = TextView(row.context)
    private val numberView = TextView(row.context)
    private val icons = LinearLayout(row.context)

    init {
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.BOTTOM
        row.layoutParams = row.listParams()
        avatar.layoutParams = LinearLayout.LayoutParams(row.dp(40), row.dp(40))
        row.addView(avatar)
        bubble.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(row.dp(16), row.dp(10), row.dp(16), row.dp(10))
            background = GradientDrawable().apply {
                val r = row.dpf(20f)
                val tail = row.dpf(4f)
                cornerRadii = floatArrayOf(r, r, r, r, r, r, tail, tail)
                setColor(OmnioColors.CARD)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = row.dp(10)
                marginEnd = row.dp(48)
            }
        }
        nameView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        numberView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(OmnioColors.TEXT_MUTED)
            maxLines = 1
        }
        icons.setPadding(0, row.dp(4), 0, 0)
        bubble.addView(nameView)
        bubble.addView(numberView)
        bubble.addView(icons)
        row.addView(bubble)
    }

    fun bind(person: OmnioPeople.Person) {
        val v = verticalFor(row, host.density, 5)
        row.setPadding(row.dp(16), v, row.dp(16), v)
        OmnioDisplay.loadAvatar(avatar, person.photo, person.name, row.dp(20))
        avatar.setOnClickListener { host.photo(person) }
        nameView.text = host.name(person)
        nameView.setTextColor(host.textColor)
        numberView.text = person.mainNumber
        numberView.visibility = if (person.mainNumber.isEmpty()) View.GONE else View.VISIBLE
        badgeRow(icons, host.badges(person))
        bubble.setOnClickListener { host.click(person) }
        bubble.setOnLongClickListener { host.longClick(person, row); true }
        row.setOnClickListener { host.click(person) }
    }
}

/** Timeline: a line runs down the left with a dot for each person. */
class TimelineHolder(private val row: LinearLayout, private val host: ContactStyleHost) : RecyclerView.ViewHolder(row) {
    private val dot = View(row.context)
    private val avatar = ImageView(row.context)
    private val nameView = TextView(row.context)
    private val numberView = TextView(row.context)

    init {
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = row.listParams()
        row.addView(FrameLayout(row.context).apply {
            layoutParams = LinearLayout.LayoutParams(row.dp(44), ViewGroup.LayoutParams.MATCH_PARENT)
            addView(View(context).apply {
                setBackgroundColor(OmnioColors.CARD_SELECTED)
                layoutParams = FrameLayout.LayoutParams(row.dp(2), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL)
            })
            addView(dot.apply { layoutParams = FrameLayout.LayoutParams(row.dp(10), row.dp(10), Gravity.CENTER) })
        })
        avatar.layoutParams = LinearLayout.LayoutParams(row.dp(42), row.dp(42)).apply { marginStart = row.dp(4) }
        row.addView(avatar)
        row.addView(LinearLayout(row.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(row.dp(14), 0, row.dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(nameView.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(numberView.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(OmnioColors.TEXT_MUTED)
                maxLines = 1
            })
        })
    }

    fun bind(person: OmnioPeople.Person) {
        val v = verticalFor(row, host.density, 8)
        row.setPadding(row.dp(8), v, 0, v)
        ripple(row)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(OmnioDisplay.colourFor(person.name))
            setStroke(row.dp(2), OmnioColors.BACKGROUND)
        }
        OmnioDisplay.loadAvatar(avatar, person.photo, person.name, row.dp(21))
        avatar.setOnClickListener { host.photo(person) }
        nameView.text = host.name(person)
        nameView.setTextColor(host.textColor)
        numberView.text = person.mainNumber
        row.setOnClickListener { host.click(person) }
        row.setOnLongClickListener { host.longClick(person, row); true }
    }
}

/** Timeline letter marker: the letter in a circle on the line. */
class TimelineHeaderHolder(private val row: LinearLayout) : RecyclerView.ViewHolder(row) {
    private val letterView = TextView(row.context)

    init {
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = row.listParams()
        row.setPadding(row.dp(8), row.dp(10), 0, row.dp(4))
        row.addView(FrameLayout(row.context).apply {
            layoutParams = LinearLayout.LayoutParams(row.dp(44), row.dp(34))
            addView(View(context).apply {
                setBackgroundColor(OmnioColors.CARD_SELECTED)
                layoutParams = FrameLayout.LayoutParams(row.dp(2), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL)
            })
            addView(letterView.apply {
                gravity = Gravity.CENTER
                setTextColor(OmnioColors.ON_ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(OmnioColors.CONTACTS)
                }
                layoutParams = FrameLayout.LayoutParams(row.dp(30), row.dp(30), Gravity.CENTER)
            })
        })
    }

    fun bind(letter: String) {
        letterView.text = letter
    }
}

/** Minimal: big clean names, first letter in colour. */
class MinimalHolder(private val view: TextView, private val host: ContactStyleHost) : RecyclerView.ViewHolder(view) {
    init {
        view.layoutParams = view.listParams()
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        view.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        view.maxLines = 1
        view.ellipsize = TextUtils.TruncateAt.END
    }

    fun bind(person: OmnioPeople.Person) {
        val v = verticalFor(view, host.density, 10)
        view.setPadding(view.dp(26), v, view.dp(40), v)
        ripple(view)
        val name = host.name(person)
        val text = SpannableString(name)
        if (name.isNotEmpty() && text.getSpans(0, 1, ForegroundColorSpan::class.java).isEmpty()) {
            text.setSpan(ForegroundColorSpan(OmnioDisplay.colourFor(person.name)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = text
        view.setTextColor(host.textColor)
        view.setOnClickListener { host.click(person) }
        view.setOnLongClickListener { host.longClick(person, view); true }
    }
}

/** Business card: coloured edge, company and job, number and email, quick buttons. */
class BusinessHolder(private val card: LinearLayout, private val host: ContactStyleHost) : RecyclerView.ViewHolder(card) {
    private val stripe = View(card.context)
    private val avatar = ImageView(card.context)
    private val nameView = TextView(card.context)
    private val workView = TextView(card.context)
    private val numberView = TextView(card.context)
    private val emailView = TextView(card.context)
    private val textButton = roundButton(card, R.drawable.ic_omnio_chat, OmnioColors.MESSAGES)
    private val callButton = roundButton(card, com.omnioone.commons.R.drawable.ic_phone_vector, OmnioColors.CALLS)

    init {
        card.orientation = LinearLayout.HORIZONTAL
        card.layoutParams = card.listParams(card.dp(14), card.dp(6), card.dp(14), card.dp(6))
        card.clipToOutline = true
        card.addView(stripe.apply { layoutParams = LinearLayout.LayoutParams(card.dp(6), ViewGroup.LayoutParams.MATCH_PARENT) })
        card.addView(LinearLayout(card.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(card.dp(14), card.dp(14), card.dp(12), card.dp(14))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                avatar.layoutParams = LinearLayout.LayoutParams(card.dp(44), card.dp(44))
                addView(avatar)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(card.dp(12), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(nameView.apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        setTypeface(typeface, Typeface.BOLD)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(workView.apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(OmnioColors.TEXT_MUTED)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                })
                addView(textButton)
                addView(callButton)
            })
            addView(numberView.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, card.dp(10), 0, 0)
                maxLines = 1
            })
            addView(emailView.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, card.dp(2), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        })
    }

    fun bind(person: OmnioPeople.Person) {
        val colour = OmnioDisplay.colourFor(person.name)
        card.background = GradientDrawable().apply {
            cornerRadius = card.dpf(18f)
            setColor(OmnioColors.CARD_DEEP)
        }
        stripe.setBackgroundColor(colour)
        OmnioDisplay.loadAvatar(avatar, person.photo, person.name, card.dp(22))
        avatar.setOnClickListener { host.photo(person) }
        nameView.text = host.name(person)
        nameView.setTextColor(host.textColor)
        val work = listOf(person.jobTitle, person.company).filter { it.isNotBlank() }.joinToString(" · ")
        workView.text = work
        workView.visibility = if (work.isEmpty()) View.GONE else View.VISIBLE
        numberView.text = "📞  " + person.mainNumber
        numberView.setTextColor(host.textColor)
        numberView.visibility = if (person.mainNumber.isEmpty()) View.GONE else View.VISIBLE
        val email = person.emails.firstOrNull().orEmpty()
        emailView.text = "✉  $email"
        emailView.setTextColor(host.textColor)
        emailView.visibility = if (email.isEmpty()) View.GONE else View.VISIBLE
        callButton.setOnClickListener { host.call(person) }
        textButton.setOnClickListener { host.text(person) }
        card.setOnClickListener { host.click(person) }
        card.setOnLongClickListener { host.longClick(person, card); true }
    }
}

/** Spotlight: a big photo banner per person, with call and text on it. */
class SpotlightHolder(private val frame: FrameLayout, private val host: ContactStyleHost) : RecyclerView.ViewHolder(frame) {
    private val photo = ImageView(frame.context)
    private val nameView = TextView(frame.context)
    private val numberView = TextView(frame.context)
    private val textButton = roundButton(frame, R.drawable.ic_omnio_chat, OmnioColors.MESSAGES, 44, onDark = true)
    private val callButton = roundButton(frame, com.omnioone.commons.R.drawable.ic_phone_vector, OmnioColors.CALLS, 44, onDark = true)

    init {
        frame.layoutParams = frame.listParams(frame.dp(14), frame.dp(7), frame.dp(14), frame.dp(7))
        photo.scaleType = ImageView.ScaleType.CENTER_CROP
        photo.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, frame.dp(200))
        frame.addView(photo)
        frame.addView(View(frame.context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0x00000000)).apply {
                val r = frame.dpf(22f)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, frame.dp(110), Gravity.BOTTOM)
        })
        frame.addView(LinearLayout(frame.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(frame.dp(18), 0, frame.dp(14), frame.dp(14))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(nameView.apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
                })
                addView(numberView.apply {
                    setTextColor(0xDDFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    maxLines = 1
                })
            })
            addView(textButton)
            addView(callButton)
        })
    }

    fun bind(person: OmnioPeople.Person) {
        keepSquare(photo, 0.62f)
        frame.clipToOutline = true
        frame.background = GradientDrawable().apply {
            cornerRadius = frame.dpf(22f)
            setColor(OmnioColors.CARD_DEEP)
        }
        OmnioDisplay.loadAvatar(photo, person.photo, person.name, frame.dp(22))
        nameView.text = host.name(person)
        numberView.text = person.mainNumber
        callButton.setOnClickListener { host.call(person) }
        textButton.setOnClickListener { host.text(person) }
        frame.setOnClickListener { host.click(person) }
        frame.setOnLongClickListener { host.longClick(person, frame); true }
    }
}

// ---------------------------------------------------------------- grid styles

/** Pills: a rounded chip with a small photo and first name. */
class PillHolder(private val cell: FrameLayout, private val host: ContactStyleHost) : RecyclerView.ViewHolder(cell) {
    private val pill = LinearLayout(cell.context)
    private val avatar = ImageView(cell.context)
    private val nameView = TextView(cell.context)

    init {
        cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cell.setPadding(cell.dp(4), cell.dp(5), cell.dp(4), cell.dp(5))
        pill.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(cell.dp(5), cell.dp(5), cell.dp(14), cell.dp(5))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        avatar.layoutParams = LinearLayout.LayoutParams(cell.dp(34), cell.dp(34))
        pill.addView(avatar)
        pill.addView(nameView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(cell.dp(9), 0, 0, 0)
        })
        cell.addView(pill)
    }

    fun bind(person: OmnioPeople.Person) {
        pill.background = GradientDrawable().apply {
            cornerRadius = cell.dpf(24f)
            setColor(OmnioColors.CARD)
            setStroke(cell.dp(1), soft(OmnioDisplay.colourFor(person.name), 0x55))
        }
        ripple(pill)
        OmnioDisplay.loadAvatar(avatar, person.photo, person.name, cell.dp(17))
        nameView.text = host.name(person).toString().trim().substringBefore(" ")
        nameView.setTextColor(host.textColor)
        pill.setOnClickListener { host.click(person) }
        pill.setOnLongClickListener { host.longClick(person, cell); true }
    }
}

/** Polaroid: photos in white frames with a handwritten-style caption, slightly tilted. */
class PolaroidHolder(private val cell: FrameLayout, private val host: ContactStyleHost) : RecyclerView.ViewHolder(cell) {
    private val frame = LinearLayout(cell.context)
    private val photo = ImageView(cell.context)
    private val caption = TextView(cell.context)

    init {
        cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cell.setPadding(cell.dp(10), cell.dp(12), cell.dp(10), cell.dp(12))
        cell.clipChildren = false
        frame.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cell.dp(7), cell.dp(7), cell.dp(7), 0)
            elevation = cell.dpf(4f)
            background = GradientDrawable().apply {
                cornerRadius = cell.dpf(3f)
                setColor(0xFFF7F5EF.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        photo.scaleType = ImageView.ScaleType.CENTER_CROP
        photo.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cell.dp(120))
        frame.addView(photo)
        frame.addView(caption.apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF2B2B2B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("casual", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(cell.dp(2), cell.dp(8), cell.dp(2), cell.dp(10))
        })
        cell.addView(frame)
    }

    fun bind(person: OmnioPeople.Person, position: Int) {
        keepSquare(photo)
        val tilts = floatArrayOf(-2.5f, 1.8f, -1.2f, 2.6f, 0.8f, -2f)
        frame.rotation = tilts[position % tilts.size]
        OmnioDisplay.loadAvatar(photo, person.photo, person.name, cell.dp(2))
        caption.text = host.name(person).toString().trim().substringBefore(" ")
        frame.setOnClickListener { host.click(person) }
        frame.setOnLongClickListener { host.longClick(person, cell); true }
    }
}

/** Orbit: the photo inside the four-colour Omnio ring, which spins when tapped. */
class OrbitHolder(private val cell: FrameLayout, private val host: ContactStyleHost) : RecyclerView.ViewHolder(cell) {
    private val square = FrameLayout(cell.context)
    private val ring = OrbitRing(cell.context, cell.dpf(3f))
    private val avatar = ImageView(cell.context)
    private val nameView = TextView(cell.context)

    init {
        cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cell.setPadding(cell.dp(6), cell.dp(8), cell.dp(6), cell.dp(8))
        val column = LinearLayout(cell.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        square.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cell.dp(90))
        ring.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        square.addView(ring)
        square.addView(avatar)
        column.addView(square)
        column.addView(nameView.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, cell.dp(6), 0, 0)
        })
        cell.addView(column)
    }

    fun bind(person: OmnioPeople.Person) {
        square.post {
            val width = square.width
            if (width > 0 && square.layoutParams.height != width) square.layoutParams = square.layoutParams.apply { height = width }
            val inset = (width * 0.11f).toInt()
            avatar.layoutParams = FrameLayout.LayoutParams(width - inset * 2, width - inset * 2, Gravity.CENTER)
        }
        ring.rotation = (Math.abs(person.name.hashCode()) % 360).toFloat()
        OmnioDisplay.loadAvatar(avatar, person.photo, person.name, cell.dp(200))
        nameView.text = host.name(person)
        nameView.setTextColor(host.textColor)
        cell.setOnClickListener {
            ring.animate().cancel()
            ring.animate().rotationBy(360f).setDuration(450).setInterpolator(DecelerateInterpolator())
                .withEndAction { host.click(person) }.start()
        }
        cell.setOnLongClickListener { host.longClick(person, cell); true }
        ripple(cell)
    }
}

/** Monogram: bold coloured initials instead of photos. */
class MonogramHolder(private val cell: FrameLayout, private val host: ContactStyleHost) : RecyclerView.ViewHolder(cell) {
    private val circle = TextView(cell.context)
    private val nameView = TextView(cell.context)

    init {
        cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cell.setPadding(cell.dp(6), cell.dp(8), cell.dp(6), cell.dp(8))
        cell.addView(LinearLayout(cell.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(circle.apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cell.dp(70)).apply {
                    marginStart = cell.dp(4)
                    marginEnd = cell.dp(4)
                }
            })
            addView(nameView.apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, cell.dp(6), 0, 0)
            })
        })
    }

    fun bind(person: OmnioPeople.Person) {
        circle.post {
            val width = circle.width
            if (width > 0 && circle.layoutParams.height != width) circle.layoutParams = circle.layoutParams.apply { height = width }
            circle.setTextSize(TypedValue.COMPLEX_UNIT_PX, width * 0.36f)
        }
        val colour = OmnioDisplay.colourFor(person.name)
        circle.text = initials(person.name)
        circle.setTextColor(colour)
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(soft(colour, 0x2E))
            setStroke(cell.dp(2), soft(colour, 0x99))
        }
        nameView.text = host.name(person)
        nameView.setTextColor(host.textColor)
        ripple(cell)
        cell.setOnClickListener { host.click(person) }
        cell.setOnLongClickListener { host.longClick(person, cell); true }
    }
}

/** Honeycomb: hexagon photos, rows staggered like a honeycomb. */
class HoneycombHolder(private val cell: FrameLayout, private val host: ContactStyleHost, private val columns: () -> Int) : RecyclerView.ViewHolder(cell) {
    private val column = LinearLayout(cell.context)
    private val photo = ImageView(cell.context)
    private val nameView = TextView(cell.context)

    init {
        cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cell.setPadding(cell.dp(2), cell.dp(2), cell.dp(2), cell.dp(4))
        column.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        photo.layoutParams = LinearLayout.LayoutParams(cell.dp(80), cell.dp(80))
        column.addView(photo)
        column.addView(nameView.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(cell.dp(2), cell.dp(4), cell.dp(2), 0)
        })
        cell.addView(column)
    }

    fun bind(person: OmnioPeople.Person, position: Int) {
        cell.post {
            val size = (cell.width * 0.84f).toInt()
            if (size > 0 && photo.layoutParams.width != size) photo.layoutParams = LinearLayout.LayoutParams(size, size)
            // every other row shifts sideways, so the hexagons interlock
            val row = position / columns().coerceAtLeast(1)
            column.translationX = if (row % 2 == 0) -cell.width * 0.08f else cell.width * 0.08f
        }
        val placeholder = BitmapDrawable(cell.resources, OmnioHexagon.mask(OmnioDisplay.letterTile(cell.context, person.name, 0f)))
        Glide.with(cell.context).load(person.photo.ifEmpty { null })
            .transform(OmnioHexagon())
            .placeholder(placeholder).error(placeholder).fallback(placeholder)
            .into(photo)
        photo.setOnClickListener { host.click(person) }
        nameView.text = host.name(person).toString().trim().substringBefore(" ")
        nameView.setTextColor(host.textColor)
        cell.setOnClickListener { host.click(person) }
        cell.setOnLongClickListener { host.longClick(person, cell); true }
    }
}

/** Cuts a photo into a pointy-topped hexagon. */
class OmnioHexagon : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val size = minOf(outWidth, outHeight).coerceAtLeast(1)
        return mask(TransformationUtils.centerCrop(pool, toTransform, size, size))
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID.toByteArray())
    }

    override fun equals(other: Any?) = other is OmnioHexagon
    override fun hashCode() = ID.hashCode()

    companion object {
        private const val ID = "com.omnioone.app.adapters.OmnioHexagon.1"

        fun mask(source: Bitmap): Bitmap {
            val size = minOf(source.width, source.height)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val path = Path()
            val r = size / 2f
            for (i in 0 until 6) {
                val angle = Math.toRadians((60.0 * i) - 90.0)
                val x = (r + r * Math.cos(angle)).toFloat()
                val y = (r + r * Math.sin(angle)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            Canvas(out).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            })
            return out
        }
    }
}
