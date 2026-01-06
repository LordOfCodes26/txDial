package com.android.dialer.fragments

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.AttributeSet
import com.android.dialer.helpers.sharedGson
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.views.MyGridLayoutManager
import com.goodwy.commons.views.MyLinearLayoutManager
import com.android.dialer.BuildConfig
import com.android.dialer.R
import com.android.dialer.activities.MainActivity
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.adapters.ContactsAdapter
import com.android.dialer.databinding.FragmentFavoritesBinding
import com.android.dialer.databinding.FragmentLettersLayoutBinding
import com.android.dialer.extensions.config
import com.android.dialer.extensions.getContactsWithSecureBoxFilter
import com.android.dialer.extensions.launchSendSMSIntentRecommendation
import com.android.dialer.extensions.setupWithContacts
import com.android.dialer.extensions.startCallWithConfirmationCheck
import com.android.dialer.extensions.startContactDetailsIntent
import com.android.dialer.extensions.startContactDetailsIntentRecommendation
import com.android.dialer.extensions.startContactEdit
import com.android.dialer.helpers.Converters
import com.android.dialer.helpers.SWIPE_ACTION_CALL
import com.android.dialer.helpers.SWIPE_ACTION_EDIT
import com.android.dialer.helpers.SWIPE_ACTION_MESSAGE
import com.android.dialer.helpers.SWIPE_ACTION_OPEN
import com.android.dialer.interfaces.RefreshItemsListener

class FavoritesFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LettersInnerBinding>(context, attributeSet),
    RefreshItemsListener {
    private lateinit var binding: FragmentLettersLayoutBinding
    private var allContacts = mutableListOf<Contact>()
    private var contactObserver: ContentObserver? = null
    private var isResumed = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentLettersLayoutBinding.bind(FragmentFavoritesBinding.bind(this).favoritesFragment)
        innerBinding = LettersInnerBinding(binding)
    }

    override fun setupFragment() {
        val useSurfaceColor = context.isDynamicTheme() && !context.isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) context.getSurfaceColor() else context.getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)

        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.no_contacts_found
        } else {
            R.string.could_not_access_contacts
        }

        binding.fragmentPlaceholder.text = context.getString(placeholderResId)
        // Show placeholder initially until data is loaded
        // It will be updated in gotContacts() after refreshItems() completes
        // This ensures the placeholder is visible even if refreshItems() hasn't been called yet
        binding.fragmentPlaceholder.beVisible()
        binding.fragmentList.beGone()
        binding.fragmentPlaceholder2.beGone()
        binding.letterFastscrollerThumb.beGone()
        binding.letterFastscroller.beGone()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, accentColor: Int) {
        binding.apply {
            fragmentPlaceholder.setTextColor(textColor)
            (fragmentList.adapter as? MyRecyclerViewAdapter)?.apply {
                updateTextColor(textColor)
                updatePrimaryColor()
                updateBackgroundColor(context.getProperBackgroundColor())
            }

            letterFastscroller.textColor = textColor.getColorStateList()
            letterFastscroller.pressedTextColor = accentColor
            letterFastscrollerThumb.setupWithFastScroller(letterFastscroller)
            letterFastscrollerThumb.textColor = accentColor.getContrastColor()
            letterFastscrollerThumb.thumbColor = accentColor.getColorStateList()
        }
    }

    override fun refreshItems(invalidate: Boolean, needUpdate: Boolean, callback: (() -> Unit)?) {
        // Optimize: Use loadExtendedFields=false for faster loading
        // Favorites don't need extended fields for display
        ContactsHelper(context).getContactsWithSecureBoxFilter(
            loadExtendedFields = false
        ) { contacts ->
            allContacts = contacts
            val favorites = contacts.filter { it.starred == 1 } as ArrayList<Contact>

            allContacts = if (activity!!.config.isCustomOrderSelected) {
                sortByCustomOrder(favorites)
            } else {
                favorites
            }

            activity?.runOnUiThread {
                gotContacts(ArrayList(allContacts))
                callback?.invoke()
            }
        }
    }
    
    fun onFragmentResume() {
        isResumed = true
        // Register ContentObserver to detect contact changes
        registerContactObserver()
        // Refresh on resume to catch changes made while app was in background
        refreshItems(needUpdate = true)
    }
    
    fun onFragmentPause() {
        isResumed = false
        unregisterContactObserver()
    }
    
    private fun registerContactObserver() {
        if (contactObserver == null && context.hasPermission(PERMISSION_READ_CONTACTS)) {
            contactObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (!selfChange && isResumed) {
                        // Debounce: wait a bit before refreshing to avoid multiple rapid refreshes
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (isResumed) {
                                refreshItems(needUpdate = true)
                            }
                        }, 500) // 500ms debounce
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                contactObserver!!
            )
        }
    }
    
    private fun unregisterContactObserver() {
        contactObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contactObserver = null
        }
    }

    private fun gotContacts(contacts: ArrayList<Contact>) {
        setupLetterFastScroller(contacts)
        binding.apply {
            if (contacts.isEmpty()) {
                fragmentPlaceholder.beVisible()
                fragmentList.beGone()
            } else {
                fragmentPlaceholder.beGone()
                fragmentList.beVisible()

//                fragmentList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
//                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
//                        super.onScrollStateChanged(recyclerView, newState)
//                        activity?.hideKeyboard()
//                    }
//                })
                fragmentList.setOnTouchListener { _, _ ->
                    activity?.hideKeyboard()
                    false
                }

                updateListAdapter()
            }
        }
    }

    private fun updateListAdapter() {
        val viewType = context.config.viewType
        setViewType(viewType, allContacts.size)

        val currAdapter = binding.fragmentList.adapter as ContactsAdapter?
        if (currAdapter == null) {
            ContactsAdapter(
                activity = activity as SimpleActivity,
                contacts = allContacts,
                recyclerView = binding.fragmentList,
                refreshItemsListener = this,
                showIcon = context.config.onFavoriteClick != SWIPE_ACTION_OPEN,
                viewType = viewType,
                showDeleteButton = false,
                enableDrag = true,
                showNumber = context.baseConfig.showPhoneNumbers,
                itemClick = {
                    itemClickAction(context.config.onFavoriteClick, it as Contact)
                },
                profileIconClick = {
                    activity?.startContactDetailsIntent(it as Contact)
                }).apply {
                binding.fragmentList.adapter = this

                onDragEndListener = {
                    val adapter = binding.fragmentList.adapter
                    if (adapter is ContactsAdapter) {
                        val items = adapter.contacts
                        saveCustomOrderToPrefs(items)
                        setupLetterFastScroller(items)
                        (activity as MainActivity).cacheFavorites(items)
                    }
                }

                onSpanCountListener = { newSpanCount ->
                    context.config.contactsGridColumnCount = newSpanCount
                }
            }

            if (context.areSystemAnimationsEnabled) {
                binding.fragmentList.scheduleLayoutAnimation()
            }
            (activity as MainActivity).cacheFavorites(allContacts)
        } else {
            currAdapter.viewType = viewType
            currAdapter.updateItems(allContacts)
            (activity as MainActivity).cacheFavorites(allContacts)
        }
    }

    fun columnCountChanged() {
        if (binding.fragmentList.layoutManager is MyGridLayoutManager) (binding.fragmentList.layoutManager as MyGridLayoutManager).spanCount = context!!.config.contactsGridColumnCount
        binding.fragmentList.adapter?.apply {
            notifyItemRangeChanged(0, allContacts.size)
        }
    }

    private fun sortByCustomOrder(favorites: List<Contact>): ArrayList<Contact> {
        val favoritesOrder = activity!!.config.favoritesContactsOrder

        if (favoritesOrder.isEmpty()) {
            return ArrayList(favorites)
        }

        val orderList = Converters().jsonToStringList(favoritesOrder)
        val map = orderList.withIndex().associate { it.value to it.index }
        val sorted = favorites.sortedBy { map[it.contactId.toString()] }

        return ArrayList(sorted)
    }

    private fun saveCustomOrderToPrefs(items: List<Contact>) {
        activity?.apply {
            val orderIds = items.map { it.contactId }
            val orderGsonString = sharedGson.toJson(orderIds)
            config.favoritesContactsOrder = orderGsonString
            allContacts = items.toMutableList()
        }
    }

    private fun setupLetterFastScroller(contacts: List<Contact>) {
        binding.letterFastscroller.setupWithContacts(binding.fragmentList, contacts)
    }

    override fun onSearchClosed() {
        binding.fragmentPlaceholder.beVisibleIf(allContacts.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(allContacts)
        setupLetterFastScroller(allContacts)
    }

    override fun onSearchQueryChanged(text: String) {
        val fixedText = text.trim().replace("\\s+".toRegex(), " ")
        val shouldNormalize = fixedText.normalizeString() == fixedText
        // Check if text contains digits for phone number search
        val hasDigits = fixedText.any { it.isDigit() }
        val numericText = if (hasDigits) fixedText.filter { it.isDigit() || it == '+' } else ""
        
        val contacts = allContacts.filter { contact ->
            getProperText(contact.getNameToDisplay(), shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.nickname, shouldNormalize).contains(fixedText, true) ||
                (hasDigits && numericText.isNotEmpty() && contact.doesContainPhoneNumber(numericText, convertLetters = false, search = true)) ||
                contact.emails.any { it.value.contains(fixedText, true) } ||
                contact.addresses.any { getProperText(it.value, shouldNormalize).contains(fixedText, true) } ||
                contact.IMs.any { it.value.contains(fixedText, true) } ||
                getProperText(contact.notes, shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.organization.company, shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.organization.jobPosition, shouldNormalize).contains(fixedText, true) ||
                contact.websites.any { it.contains(fixedText, true) }
        }.sortedByDescending {
            it.name.startsWith(fixedText, true)
        }.toMutableList() as ArrayList<Contact>

        binding.fragmentPlaceholder.beVisibleIf(contacts.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(contacts, fixedText)
        setupLetterFastScroller(contacts)
    }

    private fun setViewType(viewType: Int, size: Int = 0) {
        val spanCount = context.config.contactsGridColumnCount

        val layoutManager = if (viewType == VIEW_TYPE_GRID) {
            binding.letterFastscroller.beGone()
            MyGridLayoutManager(context, spanCount)
        } else {
            binding.letterFastscroller.beGone()
//            binding.letterFastscroller.beVisibleIf(size > 10)
//            if (size > 50) binding.letterFastscroller.textAppearanceRes = R.style.DialpadLetterStyleTiny
//            else if (size > 30) binding.letterFastscroller.textAppearanceRes = R.style.DialpadLetterStyleSmall
            MyLinearLayoutManager(context)
        }
        binding.fragmentList.layoutManager = layoutManager
    }

    override fun myRecyclerView() = binding.fragmentList

    private fun itemClickAction(action: Int, contact: Contact) {
        when (action) {
            SWIPE_ACTION_MESSAGE -> actionSMS(contact)
            SWIPE_ACTION_CALL -> actionCall(contact)
            SWIPE_ACTION_OPEN -> actionOpen(contact)
            SWIPE_ACTION_EDIT -> activity?.startContactEdit(contact)
            else -> {}
        }
    }

    private fun actionCall(contact: Contact) {
        activity?.startCallWithConfirmationCheck(contact)
    }

    private fun actionSMS(contact: Contact) {
        activity?.initiateCall(contact) { activity?.launchSendSMSIntentRecommendation(it) }
    }

    private fun actionOpen(contact: Contact) {
        activity?.startContactDetailsIntentRecommendation(contact)
    }
}
