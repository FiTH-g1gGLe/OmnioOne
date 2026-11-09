package com.omnioone.messages.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import com.google.gson.Gson
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.omnioone.commons.extensions.applyColorFilter
import com.omnioone.commons.extensions.areSystemAnimationsEnabled
import com.omnioone.commons.extensions.beGone
import com.omnioone.commons.extensions.beVisible
import com.omnioone.commons.extensions.beVisibleIf
import com.omnioone.commons.extensions.getColorStateList
import com.omnioone.commons.extensions.getContrastColor
import com.omnioone.commons.extensions.getMyContactsCursor
import com.omnioone.commons.extensions.getProperPrimaryColor
import com.omnioone.commons.extensions.getProperTextColor
import com.omnioone.commons.extensions.hasPermission
import com.omnioone.commons.extensions.hideKeyboard
import com.omnioone.commons.extensions.maybeShowNumberPickerDialog
import com.omnioone.commons.extensions.normalizeString
import com.omnioone.commons.extensions.onTextChangeListener
import com.omnioone.commons.extensions.toast
import com.omnioone.commons.extensions.underlineText
import com.omnioone.commons.extensions.updateTextColors
import com.omnioone.commons.extensions.value
import com.omnioone.commons.extensions.viewBinding
import com.omnioone.commons.helpers.MyContactsContentProvider
import com.omnioone.commons.helpers.NavigationIcon
import com.omnioone.commons.helpers.PERMISSION_READ_CONTACTS
import com.omnioone.commons.helpers.SimpleContactsHelper
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.commons.models.SimpleContact
import com.omnioone.messages.R
import com.omnioone.messages.adapters.ContactsAdapter
import com.omnioone.messages.databinding.ActivityNewConversationBinding
import com.omnioone.messages.databinding.ItemSuggestedContactBinding
import com.omnioone.messages.extensions.getSuggestedContacts
import com.omnioone.messages.extensions.getThreadId
import com.omnioone.messages.helpers.SmsIntentParser
import com.omnioone.messages.helpers.THREAD_ATTACHMENT_URI
import com.omnioone.messages.helpers.THREAD_ATTACHMENT_URIS
import com.omnioone.messages.helpers.THREAD_ID
import com.omnioone.messages.helpers.THREAD_NUMBER
import com.omnioone.messages.helpers.THREAD_TEXT
import com.omnioone.messages.helpers.THREAD_TITLE
import com.omnioone.messages.messaging.isShortCodeWithLetters
import java.net.URLDecoder
import java.util.Locale

class NewConversationActivity : SimpleActivity() {
    private var allContacts = ArrayList<SimpleContact>()
    private var privateContacts = ArrayList<SimpleContact>()

    private val binding by viewBinding(ActivityNewConversationBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.new_conversation)
        updateTextColors(binding.newConversationHolder)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.contactsList))
        setupMaterialScrollListener(
            scrollingView = binding.contactsList,
            topAppBar = binding.newConversationAppbar
        )

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        binding.newConversationAddress.requestFocus()

        // READ_CONTACTS permission is not mandatory, but without it we won't be able to show any suggestions during typing
        handlePermission(PERMISSION_READ_CONTACTS) {
            initContacts()
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.newConversationAppbar, NavigationIcon.Arrow)
        binding.noContactsPlaceholder2.setTextColor(getProperPrimaryColor())
        binding.noContactsPlaceholder2.underlineText()
        binding.suggestionsLabel.setTextColor(getProperPrimaryColor())
    }

    private fun initContacts() {
        if (isThirdPartyIntent()) {
            return
        }

        fetchContacts()
        binding.newConversationAddress.onTextChangeListener { searchString ->
            val filteredContacts = ArrayList<SimpleContact>()
            allContacts.forEach { contact ->
                if (contact.phoneNumbers.any { it.normalizedNumber.contains(searchString, true) } ||
                    contact.name.contains(searchString, true) ||
                    contact.name.contains(searchString.normalizeString(), true) ||
                    contact.name.normalizeString().contains(searchString, true)) {
                    filteredContacts.add(contact)
                }
            }

            filteredContacts.sortWith(compareBy { !it.name.startsWith(searchString, true) })
            setupAdapter(filteredContacts)

            binding.newConversationConfirm.beVisibleIf(searchString.length > 2)
        }

        binding.newConversationConfirm.applyColorFilter(getProperTextColor())
        binding.newConversationConfirm.setOnClickListener {
            val number = binding.newConversationAddress.value
            if (isShortCodeWithLetters(number)) {
                binding.newConversationAddress.setText("")
                toast(R.string.invalid_short_code, length = Toast.LENGTH_LONG)
                return@setOnClickListener
            }
            launchThreadActivity(number, number)
        }

        binding.noContactsPlaceholder2.setOnClickListener {
            handlePermission(PERMISSION_READ_CONTACTS) {
                if (it) {
                    fetchContacts()
                }
            }
        }

        val properPrimaryColor = getProperPrimaryColor()
        binding.contactsLetterFastscroller.textColor = getProperTextColor().getColorStateList()
        binding.contactsLetterFastscroller.pressedTextColor = properPrimaryColor
        binding.contactsLetterFastscrollerThumb.setupWithFastScroller(binding.contactsLetterFastscroller)
        binding.contactsLetterFastscrollerThumb.textColor = properPrimaryColor.getContrastColor()
        binding.contactsLetterFastscrollerThumb.thumbColor = properPrimaryColor.getColorStateList()
    }

    private fun isThirdPartyIntent(): Boolean {
        val result = SmsIntentParser.parse(intent)

        if (result != null && (result.first.isNotEmpty() || result.second.isNotEmpty())) {
            val (body, recipients) = result
            launchThreadActivity(
                phoneNumber = URLDecoder.decode(recipients.replace("+", "%2b").trim()),
                name = "",
                body = body
            )
            finish()
            return true
        }
        return false
    }

    private fun fetchContacts() {
        fillSuggestedContacts {
            SimpleContactsHelper(this).getAvailableContacts(false) {
                allContacts = it

                if (privateContacts.isNotEmpty()) {
                    allContacts.addAll(privateContacts)
                    allContacts.sort()
                }

                runOnUiThread {
                    setupAdapter(allContacts)
                }
            }
        }
    }

    private fun setupAdapter(contacts: ArrayList<SimpleContact>) {
        val hasContacts = contacts.isNotEmpty()
        binding.contactsList.beVisibleIf(hasContacts)
        binding.noContactsPlaceholder.beVisibleIf(!hasContacts)
        binding.noContactsPlaceholder2.beVisibleIf(
            !hasContacts && !hasPermission(
                PERMISSION_READ_CONTACTS
            )
        )

        if (!hasContacts) {
            val placeholderText = if (hasPermission(PERMISSION_READ_CONTACTS)) {
                com.omnioone.commons.R.string.no_contacts_found
            } else {
                com.omnioone.commons.R.string.no_access_to_contacts
            }

            binding.noContactsPlaceholder.text = getString(placeholderText)
        }

        val currAdapter = binding.contactsList.adapter
        if (currAdapter == null) {
            ContactsAdapter(this, contacts, binding.contactsList) {
                hideKeyboard()
                val contact = it as SimpleContact
                maybeShowNumberPickerDialog(contact.phoneNumbers) { number ->
                    launchThreadActivity(number.normalizedNumber, contact.name)
                }
            }.apply {
                binding.contactsList.adapter = this
            }

            if (areSystemAnimationsEnabled) {
                binding.contactsList.scheduleLayoutAnimation()
            }
        } else {
            (currAdapter as ContactsAdapter).updateContacts(contacts)
        }

        setupLetterFastscroller(contacts)
    }

    private fun fillSuggestedContacts(callback: () -> Unit) {
        val privateCursor = getMyContactsCursor(false, true)
        ensureBackgroundThread {
            privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val suggestions = getSuggestedContacts(privateContacts)
            runOnUiThread {
                binding.suggestionsHolder.removeAllViews()
                if (suggestions.isEmpty()) {
                    binding.suggestionsLabel.beGone()
                    binding.suggestionsScrollview.beGone()
                } else {
                    binding.suggestionsLabel.beVisible()
                    binding.suggestionsScrollview.beVisible()
                    suggestions.forEach {
                        val contact = it
                        ItemSuggestedContactBinding.inflate(layoutInflater).apply {
                            suggestedContactName.text = contact.name
                            suggestedContactName.setTextColor(getProperTextColor())

                            if (!isDestroyed) {
                                SimpleContactsHelper(this@NewConversationActivity).loadContactImage(
                                    contact.photoUri,
                                    suggestedContactImage,
                                    contact.name
                                )
                                binding.suggestionsHolder.addView(root)
                                root.setOnClickListener {
                                    launchThreadActivity(
                                        contact.phoneNumbers.first().normalizedNumber,
                                        contact.name
                                    )
                                }
                            }
                        }
                    }
                }
                callback()
            }
        }
    }

    private fun setupLetterFastscroller(contacts: ArrayList<SimpleContact>) {
        binding.contactsLetterFastscroller.setupWithRecyclerView(binding.contactsList, { position ->
            try {
                val name = contacts[position].name
                val character = if (name.isNotEmpty()) name.substring(0, 1) else ""
                FastScrollItemIndicator.Text(
                    character.uppercase(Locale.getDefault()).normalizeString()
                )
            } catch (e: Exception) {
                FastScrollItemIndicator.Text("")
            }
        })
    }

    private fun launchThreadActivity(phoneNumber: String, name: String, body: String = "") {
        hideKeyboard()
        val numbers = phoneNumber.split(";").toSet()
        val number = if (numbers.size == 1) phoneNumber else Gson().toJson(numbers)
        Intent(this, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, getThreadId(numbers))
            putExtra(THREAD_TITLE, name)
            putExtra(THREAD_TEXT, body.ifEmpty { intent.getStringExtra(Intent.EXTRA_TEXT) })
            putExtra(THREAD_NUMBER, number)

            if (intent.action == Intent.ACTION_SEND && intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URI, uri?.toString())
            } else if (intent.action == Intent.ACTION_SEND_MULTIPLE && intent.extras?.containsKey(
                    Intent.EXTRA_STREAM
                ) == true
            ) {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URIS, uris)
            }

            startActivity(this)
        }
    }
}
