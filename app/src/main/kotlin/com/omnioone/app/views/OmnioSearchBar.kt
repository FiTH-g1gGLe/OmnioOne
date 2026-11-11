package com.omnioone.app.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import com.omnioone.app.R
import com.omnioone.app.core.OmnioColors

/**
 * Omnio One's search bar at the top of the main screen: a rounded pill with a search field
 * and the menu icons (A–Z, view, settings, more).
 */
class OmnioSearchBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var isSearchOpen = false
        private set
    var onSearchOpenListener: (() -> Unit)? = null
    var onSearchClosedListener: (() -> Unit)? = null
    var onSearchTextChangedListener: ((String) -> Unit)? = null

    private val icon = ImageView(context)
    private val field = EditText(context)
    private val toolbar = MaterialToolbar(context)
    private val pill = LinearLayout(context)

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        pill.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(14), 0, dp(2), 0)
        }
        icon.apply {
            setImageResource(R.drawable.ic_search_vector)
            layoutParams = LayoutParams(dp(26), dp(26))
            setOnClickListener { if (isSearchOpen) closeSearch() else focusSearch() }
        }
        field.apply {
            hint = context.getString(R.string.omnio_search)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(14), 0, dp(4), 0)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnFocusChangeListener { _, focused -> if (focused) openSearch() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    onSearchTextChangedListener?.invoke(s?.toString().orEmpty())
                }
            })
        }
        toolbar.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        pill.addView(icon)
        pill.addView(field)
        pill.addView(toolbar)
        addView(pill, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        updateColors()
    }

    /** The menu icons live on this toolbar. */
    fun requireToolbar(): MaterialToolbar = toolbar

    fun setupMenu() {
        // the menu icons follow the bar's colours
        updateColors()
    }

    @Suppress("UNUSED_PARAMETER")
    fun toggleHideOnScroll(hide: Boolean) {}

    fun getCurrentQuery() = field.text.toString()

    private fun focusSearch() {
        field.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun openSearch() {
        if (isSearchOpen) return
        isSearchOpen = true
        icon.setImageResource(R.drawable.ic_arrow_left_vector)
        onSearchOpenListener?.invoke()
    }

    fun closeSearch() {
        isSearchOpen = false
        field.setText("")
        field.clearFocus()
        icon.setImageResource(R.drawable.ic_search_vector)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        onSearchClosedListener?.invoke()
    }

    fun updateColors() {
        setBackgroundColor(OmnioColors.BACKGROUND)
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(OmnioColors.CARD)
        }
        icon.imageTintList = ColorStateList.valueOf(OmnioColors.TEXT)
        field.setTextColor(OmnioColors.TEXT)
        field.setHintTextColor((OmnioColors.TEXT and 0x00FFFFFF) or 0x99000000.toInt())
        toolbar.overflowIcon?.setTint(OmnioColors.TEXT)
        for (i in 0 until toolbar.menu.size()) toolbar.menu.getItem(i).icon?.setTint(OmnioColors.TEXT)
    }
}
