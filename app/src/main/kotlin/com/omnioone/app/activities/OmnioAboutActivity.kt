package com.omnioone.app.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.omnioone.app.core.toast
import com.omnioone.app.core.OMNIO_GITHUB_URL
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.BuildConfig
import com.omnioone.app.R

/** Omnio One: About page: what the app is, privacy in plain words, credits and links. */
class OmnioAboutActivity : SimpleActivity() {

    private lateinit var form: LinearLayout

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OmnioColors.BACKGROUND)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(36), dp(16), dp(8))
            addView(ImageView(this@OmnioAboutActivity).apply {
                setImageResource(com.omnioone.app.R.drawable.ic_arrow_left_vector)
                imageTintList = ColorStateList.valueOf(OmnioColors.TEXT)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                background = getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { finish() }
            })
        })
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(40))
        }
        root.addView(NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(form)
        })
        setContentView(root)
        build()
    }

    private fun build() {
        // logo: the four-colour ring on navy, like the app icon
        // with the Omnio ring spinning once around it as the page opens
        val ring = com.omnioone.app.helpers.OrbitRing(this, dp(3).toFloat()).apply {
            layoutParams = FrameLayout.LayoutParams(dp(132), dp(132), Gravity.CENTER)
        }
        form.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(132), dp(132)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            addView(FrameLayout(this@OmnioAboutActivity).apply {
                layoutParams = FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF101828.toInt())
                }
                addView(ImageView(this@OmnioAboutActivity).apply {
                    setImageResource(R.drawable.ic_launcher_foreground)
                    layoutParams = FrameLayout.LayoutParams(dp(112), dp(112))
                })
            })
            addView(ring)
        })
        ring.postDelayed({
            ring.animate().rotationBy(360f).setDuration(900).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }, 250)
        form.addView(TextView(this).apply {
            text = getString(R.string.app_launcher_name)
            setTextColor(OmnioColors.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })
        form.addView(TextView(this).apply {
            text = getString(R.string.omnio_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            setTextColor(OmnioColors.TEXT)
            alpha = 0.6f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        // signature
        form.addView(TextView(this).apply {
            text = getString(R.string.omnio_signature_tagline)
            setTextColor(OmnioColors.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.ITALIC)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), 0)
        })
        listOf(R.string.omnio_signature_people, R.string.omnio_signature_made).forEach { line ->
            form.addView(TextView(this).apply {
                text = getString(line)
                setTextColor(OmnioColors.TEXT)
                alpha = 0.85f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), 0)
            })
        }

        // brand colours strip
        form.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            listOf(OmnioColors.CALLS, OmnioColors.MESSAGES, OmnioColors.CONTACTS, OmnioColors.SOCIALS).forEach { colour ->
                addView(View(this@OmnioAboutActivity).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(colour)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(6)).apply { marginStart = dp(3); marginEnd = dp(3) }
                })
            }
        })

        section("🔒", R.string.omnio_about_privacy)
        infoCard(
            R.string.omnio_about_privacy_1,
            R.string.omnio_about_privacy_2,
            R.string.omnio_about_privacy_3,
            R.string.omnio_about_privacy_4,
        )

        section("🛠️", R.string.omnio_about_project)
        linkCard(
            R.string.omnio_about_source to OMNIO_GITHUB_URL,
            R.string.omnio_about_report to "$OMNIO_GITHUB_URL/issues",
            R.string.omnio_about_releases to "$OMNIO_GITHUB_URL/releases",
        )
        actionCard(R.string.omnio_about_licences) {
            // the open-source libraries Omnio One uses, each opening its own page
            val libraries = listOf(
                "Glide (images)" to "https://github.com/bumptech/glide",
                "EventBus" to "https://github.com/greenrobot/EventBus",
                "android-smsmms (picture messages)" to "https://github.com/klinker41/android-smsmms",
                "libphonenumber" to "https://github.com/google/libphonenumber",
                "AutoFitTextView" to "https://github.com/grantland/android-autofittextview",
                "Gson" to "https://github.com/google/gson",
                "AndroidX & Material Components" to "https://developer.android.com/jetpack/androidx",
            )
            com.omnioone.app.helpers.OmnioSheet.list(this, getString(R.string.omnio_about_licences), null, libraries.map { com.omnioone.app.helpers.OmnioSheet.Item(it.first) }) { picked ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(libraries[picked].second)))
                } catch (e: Exception) {
                    toast(R.string.omnio_no_app_found)
                }
            }
        }

        // licence notice (required until every original line is rewritten)
        form.addView(TextView(this).apply {
            text = getString(R.string.omnio_about_licence_notice)
            setTextColor(OmnioColors.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(20), dp(16), 0)
        })
        form.addView(TextView(this).apply {
            text = getString(R.string.omnio_about_footer)
            setTextColor(OmnioColors.TEXT)
            alpha = 0.45f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })
    }

    private fun section(emoji: String, titleRes: Int) {
        form.addView(TextView(this).apply {
            text = emoji + "  " + getString(titleRes).uppercase()
            setTextColor(OmnioColors.TEXT)
            alpha = 0.75f
            letterSpacing = 0.05f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(26), 0, dp(8))
        })
    }

    private fun newCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.omnio_round_bg)
        backgroundTintList = ColorStateList.valueOf(OmnioColors.CARD)
        setPadding(dp(16), dp(4), dp(12), dp(4))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0x1FFFFFFF)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun infoCard(vararg lines: Int) {
        val card = newCard()
        lines.forEachIndexed { index, line ->
            if (index > 0) card.addView(divider())
            card.addView(TextView(this).apply {
                text = getString(line)
                setTextColor(OmnioColors.TEXT)
                alpha = 0.85f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(12), 0, dp(12))
            })
        }
        form.addView(card)
    }

    private fun linkRow(titleRes: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(54)
        background = getDrawable(android.R.drawable.list_selector_background)
        addView(TextView(this@OmnioAboutActivity).apply {
            text = getString(titleRes)
            setTextColor(OmnioColors.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(ImageView(this@OmnioAboutActivity).apply {
            setImageResource(com.omnioone.app.R.drawable.ic_chevron_right_vector)
            imageTintList = ColorStateList.valueOf(0x80FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        setOnClickListener { onClick() }
    }

    private fun linkCard(vararg links: Pair<Int, String>) {
        val card = newCard()
        links.forEachIndexed { index, (title, url) ->
            if (index > 0) card.addView(divider())
            card.addView(linkRow(title) { openUrl(url) })
        }
        form.addView(card)
    }

    private fun actionCard(titleRes: Int, onClick: () -> Unit) {
        form.addView(newCard().apply { addView(linkRow(titleRes, onClick)) })
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(com.omnioone.app.R.string.no_app_found)
        }
    }
}
