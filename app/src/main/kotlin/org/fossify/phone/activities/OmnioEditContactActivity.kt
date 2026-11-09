package org.fossify.phone.activities

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Email as EmailKind
import android.provider.ContactsContract.CommonDataKinds.Event as EventKind
import android.provider.ContactsContract.CommonDataKinds.Phone as PhoneKind
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.telephony.PhoneNumberUtils
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.PHOTO_ADDED
import org.fossify.commons.helpers.PHOTO_CHANGED
import org.fossify.commons.helpers.PHOTO_REMOVED
import org.fossify.commons.helpers.PHOTO_UNCHANGED
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.contacts.Address
import org.fossify.commons.models.contacts.Contact
import org.fossify.commons.models.contacts.ContactSource
import org.fossify.commons.models.contacts.Email
import org.fossify.commons.models.contacts.Event
import org.fossify.commons.models.contacts.Organization
import org.fossify.phone.R
import org.fossify.phone.helpers.OmnioSheet
import java.util.Calendar

/**
 * Omnio One: add or edit a contact without leaving the app.
 * Name, photo, numbers, emails, birthday, address, work, notes, which account to save to, delete.
 * Saving goes through the shared ContactsHelper, so links, groups and chat-app entries on the
 * contact are kept as they are.
 */
class OmnioEditContactActivity : SimpleActivity() {

    companion object {
        /** Raw contact id to edit; leave out (or 0) for a new contact. */
        const val EXTRA_CONTACT_ID = "omnio_contact_id"
        const val EXTRA_NUMBER = "omnio_number"
        const val EXTRA_EMAIL = "omnio_email"
    }

    private class Row(val holder: View, val input: EditText, var type: Int, var label: String)

    private var contact: Contact? = null
    private var photoUri: String = ""
    private var originalPhotoUri: String = ""
    private var birthday: String = ""
    private var sources = ArrayList<ContactSource>()
    private var chosenSource: ContactSource? = null

    private lateinit var photoView: ImageView
    private lateinit var photoLabel: TextView
    private lateinit var firstName: EditText
    private lateinit var surname: EditText
    private lateinit var company: EditText
    private lateinit var jobTitle: EditText
    private lateinit var address: EditText
    private lateinit var notes: EditText
    private lateinit var birthdayValue: TextView
    private lateinit var phonesHolder: LinearLayout
    private lateinit var emailsHolder: LinearLayout
    private lateinit var sourceValue: TextView
    private val phones = ArrayList<Row>()
    private val emails = ArrayList<Row>()

    private val phoneTypes = listOf(
        PhoneKind.TYPE_MOBILE to R.string.omnio_type_mobile,
        PhoneKind.TYPE_HOME to R.string.omnio_type_home,
        PhoneKind.TYPE_WORK to R.string.omnio_type_work,
        PhoneKind.TYPE_MAIN to R.string.omnio_type_main,
        PhoneKind.TYPE_OTHER to R.string.omnio_type_other,
    )

    private val emailTypes = listOf(
        EmailKind.TYPE_HOME to R.string.omnio_type_home,
        EmailKind.TYPE_WORK to R.string.omnio_type_work,
        EmailKind.TYPE_OTHER to R.string.omnio_type_other,
    )

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            photoUri = uri.toString()
            showPhoto()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        val id = intent.getIntExtra(EXTRA_CONTACT_ID, 0)
        if (id == 0) {
            intent.getStringExtra(EXTRA_NUMBER)?.let { addPhoneRow(it, PhoneKind.TYPE_MOBILE, "") }
            intent.getStringExtra(EXTRA_EMAIL)?.let { addEmailRow(it, EmailKind.TYPE_HOME, "") }
            if (phones.isEmpty()) addPhoneRow("", PhoneKind.TYPE_MOBILE, "")
            loadSources()
            firstName.requestFocus()
        } else {
            ensureBackgroundThread {
                val loaded = ContactsHelper(this).getContactWithId(id, false)
                runOnUiThread {
                    if (loaded == null) {
                        toast(org.fossify.commons.R.string.unknown_error_occurred)
                        finish()
                    } else {
                        fill(loaded)
                    }
                }
            }
        }
    }

    // ---- screen ----

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F1115.toInt())
        }

        // top bar: ✕  title  Save
        val isNew = intent.getIntExtra(EXTRA_CONTACT_ID, 0) == 0
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(36), dp(16), dp(8))
            addView(ImageView(this@OmnioEditContactActivity).apply {
                setImageResource(org.fossify.commons.R.drawable.ic_cross_vector)
                imageTintList = ColorStateList.valueOf(OmnioSheet.TEXT)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                background = getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { finish() }
            })
            addView(TextView(this@OmnioEditContactActivity).apply {
                text = getString(if (isNew) R.string.omnio_new_contact else R.string.omnio_edit_contact)
                setTextColor(OmnioSheet.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@OmnioEditContactActivity).apply {
                text = getString(org.fossify.commons.R.string.save)
                setTextColor(0xFF0F1115.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(20), 0, dp(20), 0)
                background = getDrawable(R.drawable.omnio_round_bg)
                backgroundTintList = ColorStateList.valueOf(OmnioSheet.BLUE)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42))
                setOnClickListener { save() }
            })
        })

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(40))
        }

        // photo
        photoView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(112)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setOnClickListener { photoChoices() }
        }
        form.addView(photoView)
        photoLabel = TextView(this).apply {
            setTextColor(OmnioSheet.BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { photoChoices() }
        }
        form.addView(photoLabel)

        form.addView(section(R.string.omnio_section_name))
        firstName = field(R.string.omnio_first_name, InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        surname = field(R.string.omnio_surname, InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        form.addView(firstName)
        form.addView(surname)

        form.addView(section(R.string.omnio_section_phone))
        phonesHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(phonesHolder)
        form.addView(addButton(R.string.omnio_add_phone) { addPhoneRow("", PhoneKind.TYPE_MOBILE, "", focus = true) })

        form.addView(section(R.string.omnio_email))
        emailsHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(emailsHolder)
        form.addView(addButton(R.string.omnio_add_email) { addEmailRow("", EmailKind.TYPE_HOME, "", focus = true) })

        form.addView(section(R.string.omnio_birthday))
        birthdayValue = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = getDrawable(R.drawable.omnio_round_bg)
            backgroundTintList = ColorStateList.valueOf(OmnioSheet.CARD)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            setOnClickListener { pickBirthday() }
        }
        form.addView(birthdayValue)
        showBirthday()

        form.addView(section(R.string.omnio_section_work))
        company = field(R.string.omnio_company, InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        jobTitle = field(R.string.omnio_job_title, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        form.addView(company)
        form.addView(jobTitle)

        form.addView(section(R.string.omnio_section_address))
        address = field(R.string.omnio_address, InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, multiLine = true)
        form.addView(address)

        form.addView(section(R.string.omnio_notes))
        notes = field(R.string.omnio_notes, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, multiLine = true)
        form.addView(notes)

        // account (new contacts only)
        sourceValue = TextView(this).apply {
            setTextColor(OmnioSheet.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = getDrawable(R.drawable.omnio_round_bg)
            backgroundTintList = ColorStateList.valueOf(OmnioSheet.CARD)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            setOnClickListener { pickSource() }
        }
        if (isNew) {
            form.addView(section(R.string.omnio_section_save_to))
            form.addView(sourceValue)
        } else {
            form.addView(TextView(this).apply {
                text = getString(R.string.omnio_delete_contact)
                setTextColor(OmnioSheet.RED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.omnio_round_bg)
                backgroundTintList = ColorStateList.valueOf(0xFF2A1A1D.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(32) }
                setOnClickListener { confirmDelete() }
            })
        }

        root.addView(NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(form)
        })
        setContentView(root)
        showPhoto()
    }

    private fun section(textRes: Int) = TextView(this).apply {
        text = getString(textRes)
        setTextColor(OmnioSheet.TEXT)
        alpha = 0.6f
        isAllCaps = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(22), 0, dp(8))
    }

    private fun field(hintRes: Int, extraType: Int, multiLine: Boolean = false) = EditText(this).apply {
        hint = getString(hintRes)
        styleInput(this, InputType.TYPE_CLASS_TEXT or extraType, multiLine)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    private fun styleInput(input: EditText, type: Int, multiLine: Boolean = false) {
        input.setTextColor(OmnioSheet.TEXT)
        input.setHintTextColor(0x66FFFFFF)
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        input.inputType = type or if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        if (multiLine) {
            input.minLines = 2
            input.gravity = Gravity.TOP or Gravity.START
        } else {
            input.isSingleLine = true
        }
        input.background = getDrawable(R.drawable.omnio_round_bg)
        input.backgroundTintList = ColorStateList.valueOf(OmnioSheet.CARD)
        input.setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun addButton(textRes: Int, onClick: () -> Unit) = TextView(this).apply {
        text = "+  " + getString(textRes)
        setTextColor(OmnioSheet.ORANGE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(4), dp(8), dp(4), dp(4))
        setOnClickListener { onClick() }
    }

    // ---- phone / email rows ----

    private fun typeName(type: Int, label: String, types: List<Pair<Int, Int>>): String =
        types.firstOrNull { it.first == type }?.let { getString(it.second) } ?: getPhoneNumberTypeText(type, label)

    private fun addPhoneRow(value: String, type: Int, label: String, focus: Boolean = false) =
        addRow(phonesHolder, phones, value, type, label, phoneTypes, InputType.TYPE_CLASS_PHONE, R.string.omnio_phone_hint, focus)

    private fun addEmailRow(value: String, type: Int, label: String, focus: Boolean = false) =
        addRow(
            emailsHolder, emails, value, type, label, emailTypes,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, R.string.omnio_email, focus
        )

    private fun addRow(
        holder: LinearLayout,
        rows: ArrayList<Row>,
        value: String,
        type: Int,
        label: String,
        types: List<Pair<Int, Int>>,
        inputType: Int,
        hintRes: Int,
        focus: Boolean
    ) {
        val input = EditText(this).apply {
            setText(value)
            hint = getString(hintRes)
            styleInput(this, inputType)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        val row = Row(line, input, type, label)
        val typeChip = TextView(this).apply {
            text = typeName(type, label, types)
            setTextColor(OmnioSheet.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.omnio_round_bg)
            backgroundTintList = ColorStateList.valueOf(OmnioSheet.CARD)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(8) }
        }
        typeChip.setOnClickListener {
            OmnioSheet.list(this, getString(R.string.omnio_pick_type), null, types.map { OmnioSheet.Item(getString(it.second)) }) { picked ->
                row.type = types[picked].first
                row.label = ""
                typeChip.text = getString(types[picked].second)
            }
        }
        val remove = ImageView(this).apply {
            setImageResource(org.fossify.commons.R.drawable.ic_cross_vector)
            imageTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener {
                holder.removeView(line)
                rows.remove(row)
            }
        }
        line.addView(input)
        line.addView(typeChip)
        line.addView(remove)
        holder.addView(line)
        rows.add(row)
        if (focus) input.requestFocus()
    }

    // ---- photo ----

    private fun showPhoto() {
        val name = listOf(if (::firstName.isInitialized) firstName.text.toString() else "", "").first().ifEmpty { "?" }
        if (photoUri.isEmpty()) {
            photoView.setImageDrawable(null)
            photoView.setImageBitmap(SimpleContactsHelper(this).getContactLetterIcon(name))
            photoLabel.text = getString(R.string.omnio_add_photo)
        } else {
            SimpleContactsHelper(this).loadContactImage(photoUri, photoView, name)
            photoLabel.text = getString(R.string.omnio_change_photo)
        }
    }

    private fun photoChoices() {
        val items = arrayListOf(OmnioSheet.Item(getString(R.string.omnio_choose_photo)))
        if (photoUri.isNotEmpty()) items.add(OmnioSheet.Item(getString(R.string.omnio_remove_photo), labelColor = OmnioSheet.RED))
        OmnioSheet.list(this, getString(R.string.omnio_photo), null, items) { picked ->
            if (picked == 0) {
                try {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            } else {
                photoUri = ""
                showPhoto()
            }
        }
    }

    // ---- birthday ----

    private fun showBirthday() {
        if (birthday.isEmpty()) {
            birthdayValue.text = "+  " + getString(R.string.omnio_add_birthday)
            birthdayValue.setTextColor(OmnioSheet.ORANGE)
        } else {
            birthdayValue.text = formatBirthday(birthday)
            birthdayValue.setTextColor(OmnioSheet.TEXT)
        }
    }

    /** "1990-03-14" → "14 March 1990"; "--03-14" (no year) → "14 March". */
    private fun formatBirthday(value: String): String {
        return try {
            val noYear = value.startsWith("--")
            val parts = value.removePrefix("--").split("-").map { it.toInt() }
            val (year, month, day) = if (noYear) Triple(2000, parts[0], parts[1]) else Triple(parts[0], parts[1], parts[2])
            val cal = Calendar.getInstance().apply { set(year, month - 1, day) }
            val pattern = if (noYear) "d MMMM" else "d MMMM yyyy"
            java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(cal.time)
        } catch (e: Exception) {
            value
        }
    }

    private fun pickBirthday() {
        val cal = Calendar.getInstance()
        var year = cal.get(Calendar.YEAR) - 30
        var month = cal.get(Calendar.MONTH)
        var day = cal.get(Calendar.DAY_OF_MONTH)
        try {
            if (birthday.isNotEmpty()) {
                val parts = birthday.removePrefix("--").split("-").map { it.toInt() }
                if (birthday.startsWith("--")) {
                    month = parts[0] - 1; day = parts[1]
                } else {
                    year = parts[0]; month = parts[1] - 1; day = parts[2]
                }
            }
        } catch (ignored: Exception) {
        }
        DatePickerDialog(this, { _, y, m, d ->
            birthday = String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            showBirthday()
        }, year, month, day).apply {
            if (birthday.isNotEmpty()) {
                setButton(DatePickerDialog.BUTTON_NEUTRAL, getString(R.string.omnio_remove)) { _, _ ->
                    birthday = ""
                    showBirthday()
                }
            }
        }.show()
    }

    // ---- account to save new contacts to ----

    private fun loadSources() {
        ContactsHelper(this).getSaveableContactSources { found ->
            runOnUiThread {
                sources = found
                chosenSource = found.firstOrNull { it.type.contains("google", true) } ?: found.firstOrNull()
                showSource()
            }
        }
    }

    private fun sourceName(source: ContactSource?) = when {
        source == null -> getString(R.string.omnio_phone_storage)
        source.publicName.isNotEmpty() -> source.publicName
        source.name.isNotEmpty() -> source.name
        else -> getString(R.string.omnio_phone_storage)
    }

    private fun showSource() {
        sourceValue.text = sourceName(chosenSource)
    }

    private fun pickSource() {
        if (sources.isEmpty()) return
        OmnioSheet.list(
            this,
            getString(R.string.omnio_section_save_to),
            getString(R.string.omnio_save_to_help),
            sources.map { OmnioSheet.Item(sourceName(it), trailing = if (it == chosenSource) "✓" else null) }
        ) { picked ->
            chosenSource = sources[picked]
            showSource()
        }
    }

    // ---- load / save / delete ----

    private fun fill(loaded: Contact) {
        contact = loaded
        firstName.setText(listOf(loaded.prefix, loaded.firstName, loaded.middleName).filter { it.isNotBlank() }.joinToString(" "))
        surname.setText(listOf(loaded.surname, loaded.suffix).filter { it.isNotBlank() }.joinToString(" "))
        loaded.phoneNumbers.forEach { addPhoneRow(it.value, it.type, it.label) }
        if (loaded.phoneNumbers.isEmpty()) addPhoneRow("", PhoneKind.TYPE_MOBILE, "")
        loaded.emails.forEach { addEmailRow(it.value, it.type, it.label) }
        birthday = loaded.events.firstOrNull { it.type == EventKind.TYPE_BIRTHDAY }?.value ?: ""
        showBirthday()
        company.setText(loaded.organization.company)
        jobTitle.setText(loaded.organization.jobPosition)
        address.setText(loaded.addresses.firstOrNull()?.value ?: "")
        notes.setText(loaded.notes)
        photoUri = loaded.photoUri
        originalPhotoUri = loaded.photoUri
        showPhoto()
    }

    private fun collectPhones() = phones.mapNotNull { row ->
        val value = row.input.text.toString().trim()
        if (value.isEmpty()) null else PhoneNumber(value, row.type, row.label, PhoneNumberUtils.normalizeNumber(value) ?: value)
    }

    private fun collectEmails() = emails.mapNotNull { row ->
        val value = row.input.text.toString().trim().lowercase()
        if (value.isEmpty()) null else Email(value, row.type, row.label)
    }

    private fun save() {
        val first = firstName.text.toString().trim()
        val last = surname.text.toString().trim()
        val newPhones = ArrayList(collectPhones())
        val newEmails = ArrayList(collectEmails())
        if (first.isEmpty() && last.isEmpty() && newPhones.isEmpty() && newEmails.isEmpty() && company.text.isBlank()) {
            toast(R.string.omnio_contact_empty)
            return
        }

        val existing = contact
        val target = existing ?: Contact(id = 0, contactId = 0)
        target.prefix = ""
        target.middleName = ""
        target.suffix = ""
        target.firstName = first
        target.surname = last
        target.phoneNumbers = newPhones
        target.emails = newEmails
        val otherEvents = target.events.filter { it.type != EventKind.TYPE_BIRTHDAY }
        target.events = ArrayList(otherEvents + if (birthday.isNotEmpty()) listOf(Event(birthday, EventKind.TYPE_BIRTHDAY)) else emptyList())
        target.organization = Organization(company.text.toString().trim(), jobTitle.text.toString().trim())
        val addressText = address.text.toString().trim()
        val otherAddresses = target.addresses.drop(1)
        target.addresses = ArrayList(
            (if (addressText.isNotEmpty()) listOf(Address(addressText, target.addresses.firstOrNull()?.type ?: StructuredPostal.TYPE_HOME, "")) else emptyList()) + otherAddresses
        )
        target.notes = notes.text.toString().trim()

        val photoStatus = when {
            photoUri == originalPhotoUri -> PHOTO_UNCHANGED
            photoUri.isEmpty() -> PHOTO_REMOVED
            originalPhotoUri.isEmpty() -> PHOTO_ADDED
            else -> PHOTO_CHANGED
        }
        target.photoUri = photoUri
        if (existing == null) target.source = chosenSource?.name ?: ""

        ensureBackgroundThread {
            val ok = try {
                if (existing == null) ContactsHelper(this).insertContact(target) else ContactsHelper(this).updateContact(target, photoStatus)
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    toast(if (existing == null) R.string.omnio_contact_created else R.string.omnio_contact_saved)
                    setResult(RESULT_OK)
                    finish()
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
            }
        }
    }

    private fun confirmDelete() {
        val current = contact ?: return
        OmnioSheet.confirm(
            this,
            title = getString(R.string.omnio_delete_contact_question, current.getNameToDisplay()),
            message = getString(R.string.omnio_delete_contact_help),
            positive = getString(org.fossify.commons.R.string.delete),
            mainColor = OmnioSheet.RED
        ) {
            ContactsHelper(this).deleteContact(current, false) { ok ->
                runOnUiThread {
                    if (ok) {
                        toast(R.string.omnio_contact_deleted)
                        setResult(RESULT_FIRST_USER)
                        finish()
                    } else {
                        toast(org.fossify.commons.R.string.unknown_error_occurred)
                    }
                }
            }
        }
    }
}
