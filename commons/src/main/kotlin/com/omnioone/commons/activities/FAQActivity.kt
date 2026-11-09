package com.omnioone.commons.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import kotlinx.collections.immutable.toImmutableList
import com.omnioone.commons.compose.extensions.enableEdgeToEdgeSimple
import com.omnioone.commons.compose.screens.FAQScreen
import com.omnioone.commons.compose.theme.AppThemeSurface
import com.omnioone.commons.helpers.APP_FAQ
import com.omnioone.commons.models.FAQItem

class FAQActivity : BaseComposeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeSimple()
        setContent {
            AppThemeSurface {
                val faqItems = remember { intent.getSerializableExtra(APP_FAQ) as ArrayList<FAQItem> }
                FAQScreen(
                    goBack = ::finish,
                    faqItems = faqItems.toImmutableList()
                )
            }
        }
    }
}
