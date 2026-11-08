package org.fossify.phone.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.fossify.phone.activities.OmnioContactActivity
import org.fossify.phone.helpers.OmnioSocials

/**
 * Omnio One "Link helper".
 *
 * While you're linking a contact ("Find on Facebook"), this reads the profile address shown on
 * screen in the social app (e.g. facebook.com/jones388 under "profile link") and hands it to Omnio.
 * It does nothing at all unless a link is in progress, and only the social apps listed in
 * res/xml/omnio_link_helper.xml are ever sent to it.
 */
class OmnioLinkHelperService : AccessibilityService() {

    private var lastHandedOver: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pending = OmnioSocials.getPendingLink(this) ?: return
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return

        val texts = ArrayList<CharSequence>()
        collectTexts(root, texts, 0)
        val wanted = pending.networkKey.let { if (it == "messenger") "facebook" else it }

        val url = texts.asSequence()
            .mapNotNull { profileUrlIn(it.toString()) }
            .firstOrNull { url ->
                val network = OmnioSocials.networkFor(url) ?: return@firstOrNull false
                network.key == wanted || network.key == pending.networkKey
            } ?: return

        if (url == lastHandedOver) return
        lastHandedOver = url
        startActivity(Intent(this, OmnioContactActivity::class.java).apply {
            putExtra(OmnioContactActivity.EXTRA_CONTACT_ID, pending.rawId)
            putExtra(OmnioContactActivity.EXTRA_SHARED_LINK, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun profileUrlIn(text: String): String? = if (text.length > 400) null else OmnioSocials.profileUrlIn(text)

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<CharSequence>, depth: Int) {
        if (node == null || depth > 40 || out.size > 400) return
        node.text?.let { out.add(it) }
        node.contentDescription?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out, depth + 1)
        }
    }

    override fun onInterrupt() {}

    companion object {
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = "${context.packageName}/${OmnioLinkHelperService::class.java.name}"
            return enabled.split(':').any { it.equals(me, true) }
        }
    }
}
