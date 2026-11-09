package org.fossify.phone.adapters

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.databinding.ItemContactWithoutNumberBinding
import org.fossify.commons.databinding.ItemContactWithoutNumberGridBinding
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.interfaces.ItemMoveCallback
import org.fossify.commons.interfaces.ItemTouchHelperContract
import org.fossify.commons.interfaces.StartReorderDragListener
import org.fossify.commons.models.contacts.Contact
import org.fossify.commons.views.MyRecyclerView
import org.fossify.phone.R
import org.fossify.phone.databinding.ItemOmnioContactBinding
import org.fossify.phone.databinding.ItemOmnioContactCardBinding
import org.fossify.phone.databinding.ItemOmnioContactGridBinding
import org.fossify.phone.databinding.ItemOmnioContactTileBinding
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.helpers.OmnioDisplay
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.extensions.areMultipleSIMsAvailable
import org.fossify.phone.extensions.callContactWithSim
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.interfaces.RefreshItemsListener
import java.util.Collections

class ContactsAdapter(
    activity: SimpleActivity,
    var contacts: MutableList<Contact>,
    recyclerView: MyRecyclerView,
    highlightText: String = "",
    private val refreshItemsListener: RefreshItemsListener? = null,
    var viewType: Int = VIEW_TYPE_LIST,
    private val showDeleteButton: Boolean = true,
    private val enableDrag: Boolean = false,
    private val allowLongClick: Boolean = true,
    itemClick: (Any) -> Unit,
    val profileIconClick: ((Any) -> Unit)? = null,
    private val showLetterHeaders: Boolean = false,
    /** Omnio One display style for the Contacts tab; -1 keeps Fossify's own list/grid. */
    private val omnioStyle: Int = -1
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick),
    ItemTouchHelperContract, MyRecyclerView.MyZoomListener {

    private var textToHighlight = highlightText
    var fontSize: Float = activity.getTextSize()
    private var touchHelper: ItemTouchHelper? = null
    private var startReorderDragListener: StartReorderDragListener? = null
    var onDragEndListener: (() -> Unit)? = null
    var onSpanCountListener: (Int) -> Unit = {}

    /** Omnio One: chat apps each contact uses, keyed by contact id, shown as coloured tags under the name. */
    var appTags: Map<Int, List<org.fossify.phone.helpers.OmnioAppTags.Badge>> = emptyMap()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }


    init {
        setupDragListener(true)

        if (recyclerView.layoutManager is GridLayoutManager) {
            setupZoomListener(this)
        }

        if (enableDrag) {
            touchHelper = ItemTouchHelper(ItemMoveCallback(this, viewType == VIEW_TYPE_GRID))
            touchHelper!!.attachToRecyclerView(recyclerView)

            startReorderDragListener = object : StartReorderDragListener {
                override fun requestDrag(viewHolder: RecyclerView.ViewHolder) {
                    touchHelper?.startDrag(viewHolder)
                }
            }
        }
    }

    override fun getActionMenuId() = R.menu.cab_contacts

    override fun prepareActionMode(menu: Menu) {
        val hasMultipleSIMs = activity.areMultipleSIMsAvailable()
        val isOneItemSelected = isOneItemSelected()
        val selectedNumber = getSelectedPhoneNumber().orEmpty()

        menu.apply {
            findItem(R.id.cab_call_sim_1).isVisible = hasMultipleSIMs && isOneItemSelected
            findItem(R.id.cab_call_sim_2).isVisible = hasMultipleSIMs && isOneItemSelected
            findItem(R.id.cab_remove_default_sim).isVisible = isOneItemSelected && (activity.config.getCustomSIM(selectedNumber) ?: "") != ""

            findItem(R.id.cab_delete).isVisible = showDeleteButton
            findItem(R.id.cab_create_shortcut).title = activity.getString(R.string.create_shortcut)
            findItem(R.id.cab_create_shortcut).isVisible = isOneItemSelected && isOreoPlus()
            findItem(R.id.cab_view_details).isVisible = isOneItemSelected
            findItem(R.id.cab_block_unblock_contact).isVisible = isOneItemSelected && isNougatPlus()
            getCabBlockContactTitle { title ->
                findItem(R.id.cab_block_unblock_contact).title = title
            }
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_block_unblock_contact -> tryBlockingUnblocking()
            R.id.cab_call_sim_1 -> callContact(true)
            R.id.cab_call_sim_2 -> callContact(false)
            R.id.cab_remove_default_sim -> removeDefaultSIM()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_send_sms -> sendSMS()
            R.id.cab_view_details -> viewContactDetails()
            R.id.cab_create_shortcut -> createShortcut()
            R.id.cab_select_all -> selectAll()
        }
    }

    override fun getSelectableItemCount() = contacts.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = contacts.getOrNull(position)?.rawId

    override fun getItemKeyPosition(key: Int) = contacts.indexOfFirst { it.rawId == key }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeCreated() {
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeDestroyed() {
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = Binding.getByItemViewType(viewType).inflate(layoutInflater, parent, false)
        return createViewHolder(binding.root)
    }

    override fun getItemViewType(position: Int): Int {
        return if (omnioStyle >= 0) OMNIO_VIEW_TYPE_BASE + omnioStyle else viewType
    }

    private val omnioDensity = if (omnioStyle >= 0) OmnioDisplay.getDensity(activity) else OmnioDisplay.DENSITY_NORMAL
    private val omnioFirstNameOnly = omnioStyle >= 0 && OmnioDisplay.isFirstNameOnly(activity)

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    /** Omnio One: per-style finishing touches after the shared binding code has run. */
    private fun applyOmnioStyle(binding: ItemViewBinding, contact: Contact) {
        if (omnioStyle < 0) return
        val style = omnioStyle

        if (omnioFirstNameOnly && textToHighlight.isEmpty()) {
            binding.itemContactName.text = contact.getNameToDisplay().trim().substringBefore(" ")
        }

        if (OmnioDisplay.isList(style)) {
            val vertical = when (omnioDensity) {
                OmnioDisplay.DENSITY_COMPACT -> dp(2)
                OmnioDisplay.DENSITY_COMFY -> dp(14)
                else -> dp(8)
            }
            val compact = style == OmnioDisplay.STYLE_COMPACT
            binding.itemContactFrame.apply {
                setPadding(paddingLeft, if (compact) dp(2) else vertical, paddingRight, if (compact) dp(2) else vertical)
                minHeight = if (compact) dp(44) else dp(64)
            }
            binding.itemContactImage.layoutParams = binding.itemContactImage.layoutParams.apply {
                val size = if (compact) dp(34) else dp(48)
                width = size
                height = size
            }
            if (compact) {
                binding.itemContactName.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 0.92f)
                binding.itemContactTags?.beGone()
                binding.itemContactAppIcons?.beGone()
            }
        }

        if (style == OmnioDisplay.STYLE_DETAILED || style == OmnioDisplay.STYLE_CARDS) {
            binding.itemContactTags?.apply {
                val number = (contact.phoneNumbers.firstOrNull { it.isPrimary } ?: contact.phoneNumbers.firstOrNull())?.value.orEmpty()
                text = number
                setTextColor(textColor.adjustAlpha(0.7f))
                beVisibleIf(number.isNotEmpty())
            }
        }

        if (style == OmnioDisplay.STYLE_CARDS) {
            binding.itemContactName.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 1.02f)
            binding.itemContactFrame.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (selectedKeys.contains(contact.rawId)) 0xFF22304F.toInt() else 0xFF171B22.toInt()
            )
            val number = (contact.phoneNumbers.firstOrNull { it.isPrimary } ?: contact.phoneNumbers.firstOrNull())?.value
            binding.itemContactCall?.apply {
                beVisibleIf(number != null)
                setOnClickListener { (activity as SimpleActivity).startCallWithConfirmationCheck(contact) }
            }
            binding.itemContactSms?.apply {
                beVisibleIf(number != null)
                setOnClickListener { number?.let { activity.launchSendSMSIntent(it) } }
            }
        }

        if (OmnioDisplay.isGrid(style)) {
            binding.itemContactName.setTextColor(if (style == OmnioDisplay.STYLE_TILES) 0xFFFFFFFF.toInt() else textColor)
            binding.itemContactImage.setOnClickListener(null)
            binding.itemContactImage.isClickable = false
            binding.itemContactImage.background = null
            val name = contact.getNameToDisplay()
            when (style) {
                OmnioDisplay.STYLE_SQUARES -> OmnioDisplay.loadAvatar(binding.itemContactImage, contact.photoUri, name, dp(16))
                OmnioDisplay.STYLE_TILES -> OmnioDisplay.loadAvatar(binding.itemContactImage, contact.photoUri, name, dp(18))
                else -> SimpleContactsHelper(activity).loadContactImage(contact.photoUri, binding.itemContactImage, name)
            }
            if (style == OmnioDisplay.STYLE_SPEED_DIAL) {
                binding.itemContactName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            binding.itemContactFrame.alpha = if (selectedKeys.isNotEmpty() && !selectedKeys.contains(contact.rawId)) 0.55f else 1f
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]
        holder.bindView(contact, true, allowLongClick) { itemView, _ ->
            val viewType = getItemViewType(position)
            setupView(Binding.getByItemViewType(viewType).bind(itemView), contact, holder)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = contacts.size

    private fun getCabBlockContactTitle(callback: (String) -> Unit) {
        val contact = getSelectedItems().firstOrNull() ?: return callback("")

        activity.isContactBlocked(contact) { blocked ->
            val cabItemTitleRes = if (blocked) {
                R.string.unblock_contact
            } else {
                R.string.block_contact
            }

            callback(activity.getString(cabItemTitleRes))
        }
    }

    private fun tryBlockingUnblocking() {
        val contact = getSelectedItems().firstOrNull() ?: return

        activity.isContactBlocked(contact) { blocked ->
            if (blocked) {
                tryUnblocking(contact)
            } else {
                tryBlocking(contact)
            }
        }
    }

    private fun tryBlocking(contact: Contact) {
        askConfirmBlock(contact) { contactBlocked ->
            val resultMsg = if (contactBlocked) {
                R.string.block_contact_success
            } else {
                R.string.block_contact_fail
            }

            activity.toast(resultMsg)
            finishActMode()
        }
    }

    private fun tryUnblocking(contact: Contact) {
        val contactUnblocked = activity.unblockContact(contact)
        val resultMsg = if (contactUnblocked) {
            R.string.unblock_contact_success
        } else {
            R.string.unblock_contact_fail
        }

        activity.toast(resultMsg)
        finishActMode()
    }

    private fun askConfirmBlock(contact: Contact, callback: (Boolean) -> Unit) {
        val baseString = R.string.block_confirmation
        val question = String.format(resources.getString(baseString), contact.name)

        ConfirmationDialog(activity, question) {
            val contactBlocked = activity.blockContact(contact)
            callback(contactBlocked)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(newItems: List<Contact>, highlightText: String = "") {
        if (newItems.hashCode() != contacts.hashCode()) {
            contacts = ArrayList(newItems)
            textToHighlight = highlightText
            notifyDataSetChanged()
            finishActMode()
        } else if (textToHighlight != highlightText) {
            textToHighlight = highlightText
            notifyDataSetChanged()
        }
    }

    @SuppressLint("MissingPermission")
    private fun callContact(useSimOne: Boolean) {
        val number = getSelectedPhoneNumber() ?: return
        activity.callContactWithSim(number, useSimOne)
    }

    private fun removeDefaultSIM() {
        val phoneNumber = getSelectedPhoneNumber() ?: return
        activity.config.removeCustomSIM(phoneNumber)
        finishActMode()
    }

    private fun sendSMS() {
        val numbers = ArrayList<String>()
        getSelectedItems().map { simpleContact ->
            val contactNumbers = simpleContact.phoneNumbers
            val primaryNumber = contactNumbers.firstOrNull { it.isPrimary }
            val normalizedNumber = primaryNumber?.normalizedNumber ?: contactNumbers.firstOrNull()?.normalizedNumber

            if (normalizedNumber != null) {
                numbers.add(normalizedNumber)
            }
        }

        val recipient = TextUtils.join(";", numbers)
        activity.launchSendSMSIntent(recipient)
    }

    private fun viewContactDetails() {
        val contact = getSelectedItems().firstOrNull() ?: return
        activity.startContactDetailsIntent(contact)
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size
        val firstItem = getSelectedItems().firstOrNull() ?: return
        val items = if (itemsCnt == 1) {
            "\"${firstItem.getNameToDisplay()}\""
        } else {
            resources.getQuantityString(R.plurals.delete_contacts, itemsCnt, itemsCnt)
        }

        val baseString = R.string.deletion_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(activity, question) {
            activity.handlePermission(PERMISSION_WRITE_CONTACTS) {
                deleteContacts()
            }
        }
    }

    private fun deleteContacts() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val contactsToRemove = getSelectedItems()
        val positions = getSelectedItemPositions()
        contacts.removeAll(contactsToRemove)
        val idsToRemove = contactsToRemove.map { it.rawId }.toMutableList() as ArrayList<Int>

        SimpleContactsHelper(activity).deleteContactRawIDs(idsToRemove) {
            activity.runOnUiThread {
                if (contacts.isEmpty()) {
                    refreshItemsListener?.refreshItems()
                    finishActMode()
                } else {
                    removeSelectedItems(positions)
                }
            }
        }
    }

    private fun getSelectedItems() = contacts.filter { selectedKeys.contains(it.rawId) } as ArrayList<Contact>

    private fun getSelectedPhoneNumber(): String? {
        return getSelectedItems().firstOrNull()?.getPrimaryNumber()
    }

    @SuppressLint("NewApi")
    private fun createShortcut() {
        val contact = contacts.firstOrNull { selectedKeys.contains(it.rawId) } ?: return
        val manager = activity.shortcutManager
        if (manager.isRequestPinShortcutSupported) {
            SimpleContactsHelper(activity).getShortcutImage(contact.photoUri, contact.getNameToDisplay()) { image ->
                activity.runOnUiThread {
                    activity.handlePermission(PERMISSION_CALL_PHONE) { hasPermission ->
                        val action = if (hasPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
                        val intent = Intent(action).apply {
                            data = Uri.fromParts("tel", getSelectedPhoneNumber(), null)
                        }

                        val shortcut = ShortcutInfo.Builder(activity, contact.hashCode().toString())
                            .setShortLabel(contact.getNameToDisplay())
                            .setIcon(Icon.createWithBitmap(image))
                            .setIntent(intent)
                            .build()

                        manager.requestPinShortcut(shortcut, null)
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            Binding.getByItemViewType(holder.itemViewType).bind(holder.itemView).apply {
                Glide.with(activity).clear(itemContactImage)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupView(binding: ItemViewBinding, contact: Contact, holder: ViewHolder) {
        binding.apply {
            root.setupViewBackground(activity)
            itemContactFrame.isSelected = selectedKeys.contains(contact.rawId)

            itemContactHeader?.apply {
                val position = holder.bindingAdapterPosition
                val letter = contact.getFirstLetter()
                val showHeader = showLetterHeaders && textToHighlight.isEmpty() && position >= 0 &&
                    (position == 0 || contacts.getOrNull(position - 1)?.getFirstLetter() != letter)
                text = letter
                beVisibleIf(showHeader)
            }

            // Omnio One: small app icons for the chat apps and socials they use
            itemContactTags?.beGone()
            itemContactAppIcons?.apply {
                removeAllViews()
                val badges = appTags[contact.contactId].orEmpty()
                beVisibleIf(badges.isNotEmpty())
                val size = (16 * activity.resources.displayMetrics.density).toInt()
                badges.forEach { badge ->
                    addView(ImageView(activity).apply {
                        setImageDrawable(org.fossify.phone.helpers.OmnioAppTags.icon(activity, badge))
                        contentDescription = badge.name
                        layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply { marginEnd = size / 3 }
                    })
                }
            }

            itemContactImage.apply {
                if (profileIconClick != null && viewType != VIEW_TYPE_GRID) {
                    setBackgroundResource(R.drawable.selector_clickable_circle)

                    setOnClickListener {
                        if (!actModeCallback.isSelectable) {
                            profileIconClick.invoke(contact)
                        } else {
                            holder.viewClicked(contact)
                        }
                    }
                    setOnLongClickListener {
                        holder.viewLongClicked()
                        true
                    }
                }
            }

            itemContactName.apply {
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)

                val name = contact.getNameToDisplay()
                text = if (textToHighlight.isEmpty()) {
                    name
                } else {
                    val normalizedName = name.normalizeString()
                    val normalizedSearchText = textToHighlight.normalizeString()
                    if (normalizedName.contains(normalizedSearchText, true)) {
                        name.highlightTextPart(normalizedSearchText, properPrimaryColor)
                    } else {
                        var spacedTextToHighlight = textToHighlight
                        val strippedName = name.filterNot { it.isWhitespace() }
                        val strippedDigits = KeypadHelper.convertKeypadLettersToDigits(strippedName)
                        val startIndex = strippedDigits.indexOf(textToHighlight)

                        if (strippedDigits.contains(textToHighlight)) {
                            for (i in spacedTextToHighlight.indices) {
                                if (startIndex + i < name.length && name[startIndex + i].isWhitespace()) {
                                    spacedTextToHighlight = spacedTextToHighlight.replaceRange(i, i, " ")
                                }
                            }
                        }

                        name.highlightTextFromNumbers(spacedTextToHighlight, properPrimaryColor)
                    }
                }
            }

            if (enableDrag && textToHighlight.isEmpty()) {
                dragHandleIcon.apply {
                    beVisibleIf(selectedKeys.isNotEmpty())
                    applyColorFilter(textColor)
                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            startReorderDragListener?.requestDrag(holder)
                        }
                        false
                    }
                }
            } else {
                dragHandleIcon.apply {
                    beGone()
                    setOnTouchListener(null)
                }
            }

            if (!activity.isDestroyed) {
                SimpleContactsHelper(root.context).loadContactImage(contact.photoUri, itemContactImage, contact.getNameToDisplay())
            }

            applyOmnioStyle(this, contact)
        }
    }

    override fun onRowMoved(fromPosition: Int, toPosition: Int) {
        activity.config.isCustomOrderSelected = true

        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(contacts, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(contacts, i, i - 1)
            }
        }

        notifyItemMoved(fromPosition, toPosition)
    }

    override fun onRowSelected(myViewHolder: ViewHolder?) {}

    override fun onRowClear(myViewHolder: ViewHolder?) {
        onDragEndListener?.invoke()
    }

    override fun zoomIn() {
        val layoutManager = recyclerView.layoutManager
        if (layoutManager is GridLayoutManager) {
            val currentSpanCount = layoutManager.spanCount
            val newSpanCount = (currentSpanCount - 1).coerceIn(1, CONTACTS_GRID_MAX_COLUMNS_COUNT)
            layoutManager.spanCount = newSpanCount
            recyclerView.requestLayout()
            onSpanCountListener(newSpanCount)
        }
    }

    override fun zoomOut() {
        val layoutManager = recyclerView.layoutManager
        if (layoutManager is GridLayoutManager) {
            val currentSpanCount = layoutManager.spanCount
            val newSpanCount = (currentSpanCount + 1).coerceIn(1, CONTACTS_GRID_MAX_COLUMNS_COUNT)
            layoutManager.spanCount = newSpanCount
            recyclerView.requestLayout()
            onSpanCountListener(newSpanCount)
        }
    }

    companion object {
        const val OMNIO_VIEW_TYPE_BASE = 100
    }

    private sealed interface Binding {
        companion object {
            fun getByItemViewType(viewType: Int): Binding {
                return when (viewType) {
                    OMNIO_VIEW_TYPE_BASE + OmnioDisplay.STYLE_CARDS -> ItemOmnioCard
                    OMNIO_VIEW_TYPE_BASE + OmnioDisplay.STYLE_CIRCLES,
                    OMNIO_VIEW_TYPE_BASE + OmnioDisplay.STYLE_SQUARES,
                    OMNIO_VIEW_TYPE_BASE + OmnioDisplay.STYLE_SPEED_DIAL -> ItemOmnioGrid
                    OMNIO_VIEW_TYPE_BASE + OmnioDisplay.STYLE_TILES -> ItemOmnioTile
                    VIEW_TYPE_GRID -> ItemContactGrid
                    else -> ItemContact
                }
            }
        }

        fun inflate(layoutInflater: LayoutInflater, viewGroup: ViewGroup, attachToRoot: Boolean): ItemViewBinding

        fun bind(view: View): ItemViewBinding

        data object ItemOmnioCard : Binding {
            override fun inflate(layoutInflater: LayoutInflater, viewGroup: ViewGroup, attachToRoot: Boolean): ItemViewBinding =
                ItemOmnioCardBindingAdapter(ItemOmnioContactCardBinding.inflate(layoutInflater, viewGroup, attachToRoot))

            override fun bind(view: View): ItemViewBinding = ItemOmnioCardBindingAdapter(ItemOmnioContactCardBinding.bind(view))
        }

        data object ItemOmnioGrid : Binding {
            override fun inflate(layoutInflater: LayoutInflater, viewGroup: ViewGroup, attachToRoot: Boolean): ItemViewBinding =
                ItemOmnioGridBindingAdapter(ItemOmnioContactGridBinding.inflate(layoutInflater, viewGroup, attachToRoot))

            override fun bind(view: View): ItemViewBinding = ItemOmnioGridBindingAdapter(ItemOmnioContactGridBinding.bind(view))
        }

        data object ItemOmnioTile : Binding {
            override fun inflate(layoutInflater: LayoutInflater, viewGroup: ViewGroup, attachToRoot: Boolean): ItemViewBinding =
                ItemOmnioTileBindingAdapter(ItemOmnioContactTileBinding.inflate(layoutInflater, viewGroup, attachToRoot))

            override fun bind(view: View): ItemViewBinding = ItemOmnioTileBindingAdapter(ItemOmnioContactTileBinding.bind(view))
        }

        data object ItemContactGrid : Binding {
            override fun inflate(layoutInflater: LayoutInflater, viewGroup: ViewGroup, attachToRoot: Boolean): ItemViewBinding {
                return ItemContactGridBindingAdapter(ItemContactWithoutNumberGridBinding.inflate(layoutInflater, viewGroup, attachToRoot))
            }

            override fun bind(view: View): ItemViewBinding {
                return ItemContactGridBindingAdapter(ItemContactWithoutNumberGridBinding.bind(view))
            }
        }

        data object ItemContact : Binding {
            override fun inflate(layoutInflater: LayoutInflater, viewGroup: ViewGroup, attachToRoot: Boolean): ItemViewBinding {
                return ItemOmnioContactBindingAdapter(ItemOmnioContactBinding.inflate(layoutInflater, viewGroup, attachToRoot))
            }

            override fun bind(view: View): ItemViewBinding {
                return ItemOmnioContactBindingAdapter(ItemOmnioContactBinding.bind(view))
            }
        }
    }

    private interface ItemViewBinding : ViewBinding {
        val itemContactName: TextView
        val itemContactImage: ImageView
        val itemContactFrame: ConstraintLayout
        val dragHandleIcon: ImageView
        val itemContactHeader: TextView? get() = null
        val itemContactTags: TextView? get() = null
        val itemContactAppIcons: android.widget.LinearLayout? get() = null
        val itemContactCall: ImageView? get() = null
        val itemContactSms: ImageView? get() = null
    }

    private class ItemOmnioCardBindingAdapter(val binding: ItemOmnioContactCardBinding) : ItemViewBinding {
        override val itemContactName = binding.itemContactName
        override val itemContactImage = binding.itemContactImage
        override val itemContactFrame = binding.itemContactFrame
        override val dragHandleIcon = binding.dragHandleIcon
        override val itemContactTags = binding.itemContactTags
        override val itemContactAppIcons = binding.itemContactAppIcons
        override val itemContactCall = binding.itemContactCall
        override val itemContactSms = binding.itemContactSms

        override fun getRoot(): View = binding.root
    }

    private class ItemOmnioGridBindingAdapter(val binding: ItemOmnioContactGridBinding) : ItemViewBinding {
        override val itemContactName = binding.itemContactName
        override val itemContactImage = binding.itemContactImage
        override val itemContactFrame = binding.itemContactFrame
        override val dragHandleIcon = binding.dragHandleIcon

        override fun getRoot(): View = binding.root
    }

    private class ItemOmnioTileBindingAdapter(val binding: ItemOmnioContactTileBinding) : ItemViewBinding {
        override val itemContactName = binding.itemContactName
        override val itemContactImage = binding.itemContactImage
        override val itemContactFrame = binding.itemContactFrame
        override val dragHandleIcon = binding.dragHandleIcon

        override fun getRoot(): View = binding.root
    }

    private class ItemContactGridBindingAdapter(val binding: ItemContactWithoutNumberGridBinding) : ItemViewBinding {
        override val itemContactName = binding.itemContactName
        override val itemContactImage = binding.itemContactImage
        override val itemContactFrame = binding.itemContactFrame
        override val dragHandleIcon = binding.dragHandleIcon

        override fun getRoot(): View = binding.root
    }

    private class ItemOmnioContactBindingAdapter(val binding: ItemOmnioContactBinding) : ItemViewBinding {
        override val itemContactName = binding.itemContactName
        override val itemContactImage = binding.itemContactImage
        override val itemContactFrame = binding.itemContactFrame
        override val dragHandleIcon = binding.dragHandleIcon
        override val itemContactHeader = binding.itemContactHeader
        override val itemContactTags = binding.itemContactTags
        override val itemContactAppIcons = binding.itemContactAppIcons

        override fun getRoot(): View = binding.root
    }

    private class ItemContactBindingAdapter(val binding: ItemContactWithoutNumberBinding) : ItemViewBinding {
        override val itemContactName = binding.itemContactName
        override val itemContactImage = binding.itemContactImage
        override val itemContactFrame = binding.itemContactFrame
        override val dragHandleIcon = binding.dragHandleIcon

        override fun getRoot(): View = binding.root
    }
}
