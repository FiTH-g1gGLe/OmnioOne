package com.omnioone.messages.activities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.res.ResourcesCompat
import com.omnioone.commons.extensions.applyColorFilter
import com.omnioone.commons.extensions.beGone
import com.omnioone.commons.extensions.beVisible
import com.omnioone.commons.extensions.beVisibleIf
import com.omnioone.commons.extensions.getProperPrimaryColor
import com.omnioone.commons.extensions.getProperTextColor
import com.omnioone.commons.extensions.notificationManager
import com.omnioone.commons.extensions.updateTextColors
import com.omnioone.commons.extensions.viewBinding
import com.omnioone.commons.helpers.NavigationIcon
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.commons.models.SimpleContact
import com.omnioone.messages.adapters.ContactsAdapter
import com.omnioone.messages.databinding.ActivityConversationDetailsBinding
import com.omnioone.messages.dialogs.RenameConversationDialog
import com.omnioone.messages.extensions.config
import com.omnioone.messages.extensions.conversationsDB
import com.omnioone.messages.extensions.getContactFromAddress
import com.omnioone.messages.extensions.getThreadParticipants
import com.omnioone.messages.extensions.messagesDB
import com.omnioone.messages.extensions.renameConversation
import com.omnioone.messages.extensions.startContactDetailsIntent
import com.omnioone.messages.helpers.THREAD_ID
import com.omnioone.messages.models.Conversation

class ConversationDetailsActivity : SimpleActivity() {

    private var threadId: Long = 0L
    private var conversation: Conversation? = null
    private lateinit var participants: ArrayList<SimpleContact>

    private val binding by viewBinding(ActivityConversationDetailsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.conversationDetailsNestedScrollview))
        setupMaterialScrollListener(
            scrollingView = binding.conversationDetailsNestedScrollview,
            topAppBar = binding.conversationDetailsAppbar,
        )

        threadId = intent.getLongExtra(THREAD_ID, 0L)
        ensureBackgroundThread {
            conversation = conversationsDB.getConversationWithThreadId(threadId)
            participants = if (conversation != null && conversation!!.isScheduled) {
                val message = messagesDB.getThreadMessages(conversation!!.threadId).firstOrNull()
                message?.participants ?: arrayListOf()
            } else {
                getThreadParticipants(threadId, null)
            }
            runOnUiThread {
                setupTextViews()
                setupParticipants()
                setupCustomNotifications()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.conversationDetailsAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.conversationDetailsHolder)

        val primaryColor = getProperPrimaryColor()
        arrayOf(
            binding.notificationsHeading,
            binding.conversationNameHeading,
            binding.membersHeading
        ).forEach {
            it.setTextColor(primaryColor)
        }
    }

    private fun setupCustomNotifications() {
        binding.apply {
            notificationsHeading.beVisible()
            customNotificationsHolder.beVisible()
            customNotifications.isChecked = config.customNotifications.contains(threadId.toString())
            customNotificationsButton.beVisibleIf(customNotifications.isChecked)

            customNotificationsHolder.setOnClickListener {
                customNotifications.toggle()
                if (customNotifications.isChecked) {
                    customNotificationsButton.beVisible()
                    config.addCustomNotificationsByThreadId(threadId)
                    createNotificationChannel()
                } else {
                    customNotificationsButton.beGone()
                    config.removeCustomNotificationsByThreadId(threadId)
                    removeNotificationChannel()
                }
            }

            customNotificationsButton.setOnClickListener {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, threadId.toString())
                    startActivity(this)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val name = conversation?.title
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
            .build()

        NotificationChannel(threadId.toString(), name, NotificationManager.IMPORTANCE_HIGH).apply {
            setBypassDnd(false)
            enableLights(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                audioAttributes
            )
            enableVibration(true)
            notificationManager.createNotificationChannel(this)
        }
    }

    private fun removeNotificationChannel() {
        notificationManager.deleteNotificationChannel(threadId.toString())
    }

    private fun setupTextViews() {
        binding.conversationName.apply {
            ResourcesCompat.getDrawable(
                resources,
                com.omnioone.commons.R.drawable.ic_edit_vector,
                theme
            )?.apply {
                applyColorFilter(getProperTextColor())
                setCompoundDrawablesWithIntrinsicBounds(null, null, this, null)
            }

            text = conversation?.title
            setOnClickListener {
                RenameConversationDialog(
                    this@ConversationDetailsActivity,
                    conversation!!
                ) { title ->
                    text = title
                    ensureBackgroundThread {
                        conversation = renameConversation(conversation!!, newTitle = title)
                    }
                }
            }
        }
    }

    private fun setupParticipants() {
        val adapter = ContactsAdapter(this, participants, binding.participantsRecyclerview) {
            val contact = it as SimpleContact
            val address = contact.phoneNumbers.first().normalizedNumber
            getContactFromAddress(address) { simpleContact ->
                if (simpleContact != null) {
                    startContactDetailsIntent(simpleContact)
                }
            }
        }
        binding.participantsRecyclerview.adapter = adapter
    }
}
