package com.android.dialer.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.AttributeSet
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.contacts.Contact
import com.android.dialer.BuildConfig
import com.android.dialer.R
import com.android.dialer.activities.MainActivity
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.adapters.ContactsAdapter
import com.android.dialer.databinding.FragmentContactsBinding
import com.android.dialer.databinding.FragmentLettersLayoutBinding
import com.android.dialer.extensions.config
import com.android.dialer.extensions.getContactsWithSecureBoxFilter
import com.android.dialer.extensions.launchCreateNewContactIntent
import com.android.dialer.extensions.launchSendSMSIntentRecommendation
import com.android.dialer.extensions.setupWithContacts
import com.android.dialer.extensions.startCallWithConfirmationCheck
import com.android.dialer.extensions.startContactDetailsIntentRecommendation
import com.android.dialer.extensions.startContactEdit
import com.android.dialer.helpers.SWIPE_ACTION_CALL
import com.android.dialer.helpers.SWIPE_ACTION_EDIT
import com.android.dialer.helpers.SWIPE_ACTION_MESSAGE
import com.android.dialer.helpers.SWIPE_ACTION_OPEN
import com.android.dialer.interfaces.RefreshItemsListener
import com.android.dialer.models.RecentCall

class ContactsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LettersInnerBinding>(context, attributeSet),
    RefreshItemsListener {
    private lateinit var binding: FragmentLettersLayoutBinding
    private var allContacts = mutableListOf<Contact>()
    private var contactObserver: ContentObserver? = null
    private var isResumed = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentLettersLayoutBinding.bind(FragmentContactsBinding.bind(this).contactsFragment)
        innerBinding = LettersInnerBinding(binding)
    }

    override fun setupFragment() {
        val useSurfaceColor = context.isDynamicTheme() && !context.isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) context.getSurfaceColor() else context.getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        //binding.contactsFragment.setBackgroundColor(context.getProperBackgroundColor())

        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.no_contacts_found
        } else {
            R.string.could_not_access_contacts
        }

        binding.fragmentPlaceholder.text = context.getString(placeholderResId)

        val placeholderActionResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.create_new_contact
        } else {
            R.string.request_access
        }

        binding.fragmentPlaceholder2.apply {
            text = context.getString(placeholderActionResId)
            underlineText()
            setOnClickListener {
                if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
                    activity?.launchCreateNewContactIntent()
                } else {
                    requestReadContactsPermission()
                }
            }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, accentColor: Int) {
        binding.apply {
            (fragmentList.adapter as? MyRecyclerViewAdapter)?.apply {
                updateTextColor(textColor)
                updatePrimaryColor()
                updateBackgroundColor(context.getProperBackgroundColor())
            }
            fragmentPlaceholder.setTextColor(textColor)
            fragmentPlaceholder2.setTextColor(accentColor)

            letterFastscroller.textColor = textColor.getColorStateList()
            letterFastscroller.pressedTextColor = accentColor
            letterFastscrollerThumb.setupWithFastScroller(letterFastscroller)
            letterFastscrollerThumb.textColor = accentColor.getContrastColor()
            letterFastscrollerThumb.thumbColor = accentColor.getColorStateList()
        }
    }

    override fun refreshItems(invalidate: Boolean, needUpdate: Boolean, callback: (() -> Unit)?) {
        // Keep loadExtendedFields=true for search compatibility
        // Extended fields are needed for searching in addresses, notes, etc.
        ContactsHelper(context).getContactsWithSecureBoxFilter(
            showOnlyContactsWithNumbers = true,
            loadExtendedFields = true
        ) { contacts ->
            allContacts = contacts

            activity?.runOnUiThread {
                gotContacts(contacts)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun gotContacts(contacts: ArrayList<Contact>) {
        setupLetterFastScroller(contacts)
        if (contacts.isEmpty()) {
            binding.apply {
                fragmentPlaceholder.beVisible()
                fragmentPlaceholder2.beVisible()
                fragmentList.beGone()
            }
        } else {
            binding.apply {
                fragmentPlaceholder.beGone()
                fragmentPlaceholder2.beGone()
                fragmentList.beVisible()

                // Optimize RecyclerView for large, mostly-static contact lists
                fragmentList.setHasFixedSize(true)
                if (contacts.size > 2000) {
                    // Disable item change animations for very large lists to avoid jank
                    fragmentList.itemAnimator = null
                }

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
            }

            if (binding.fragmentList.adapter == null) {
                ContactsAdapter(
                    activity = activity as SimpleActivity,
                    contacts = contacts,
                    recyclerView = binding.fragmentList,
                    refreshItemsListener = this,
                    showIcon = context.config.onContactClick != SWIPE_ACTION_OPEN,
                    showNumber = context.baseConfig.showPhoneNumbers,
                    itemClick = {
                        itemClickAction(context.config.onContactClick, it as Contact)
                    },
                    profileIconClick = {
                        activity?.startContactDetailsIntentRecommendation(it as Contact)
                    }
                ).apply {
                    binding.fragmentList.adapter = this
                }

                if (context.areSystemAnimationsEnabled) {
                    binding.fragmentList.scheduleLayoutAnimation()
                }
            } else {
                (binding.fragmentList.adapter as ContactsAdapter).updateItems(contacts)
            }

            try {
                //Decrease the font size based on the number of letters in the letter scroller
                val allNotEmpty = contacts.filter { it.getNameToDisplay().isNotEmpty() }
                val all = allNotEmpty.map { it.getNameToDisplay().substring(0, 1) }
                val unique: Set<String> = HashSet(all)
                val sizeUnique = unique.size
                if (isHighScreenSize()) {
                    if (sizeUnique > 48) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
                    else if (sizeUnique > 37) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
                    else binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
                } else {
                    if (sizeUnique > 36) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
                    else if (sizeUnique > 30) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
                    else binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
                }
            } catch (_: Exception) { }
        }
    }

    private fun isHighScreenSize(): Boolean {
        return when (resources.configuration.screenLayout
            and Configuration.SCREENLAYOUT_LONG_MASK) {
            Configuration.SCREENLAYOUT_LONG_NO -> false
            else -> true
        }
    }

    private fun setupLetterFastScroller(contacts: ArrayList<Contact>) {
        binding.letterFastscroller.setupWithContacts(binding.fragmentList, contacts)
    }

    override fun onSearchClosed() {
        binding.fragmentPlaceholder.beVisibleIf(allContacts.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(allContacts)
        setupLetterFastScroller(ArrayList(allContacts))
    }

    override fun onSearchQueryChanged(text: String) {
        val fixedText = text.trim().replace("\\s+".toRegex(), " ")

        if (fixedText.isEmpty()) {
            // Reset to full list quickly on main thread
            binding.fragmentPlaceholder.beVisibleIf(allContacts.isEmpty())
            (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(allContacts)
            setupLetterFastScroller(ArrayList(allContacts))
            return
        }

        ensureBackgroundThread {
            val shouldNormalize = fixedText.normalizeString() == fixedText
            // Check if text contains digits for phone number search
            val hasDigits = fixedText.any { it.isDigit() }
            val numericText = if (hasDigits) fixedText.filter { it.isDigit() || it == '+' } else ""
            
            // For search, we need extended fields, so filter from allContacts which may not have them
            // If search is needed with extended fields, we should reload with loadExtendedFields=true
            val filtered = allContacts.filter { contact ->
                getProperText(contact.getNameToDisplay(), shouldNormalize).contains(fixedText, true) ||
                    getProperText(contact.nickname, shouldNormalize).contains(fixedText, true) ||
                    (hasDigits && numericText.isNotEmpty() && contact.doesContainPhoneNumber(numericText, convertLetters = false, search = true)) ||
                    contact.emails.any { it.value.contains(fixedText, true) } ||
                    contact.relations.any { it.name.contains(fixedText, true) } ||
                    contact.addresses.any { getProperText(it.value, shouldNormalize).contains(fixedText, true) } ||
                    contact.IMs.any { it.value.contains(fixedText, true) } ||
                    getProperText(contact.notes, shouldNormalize).contains(fixedText, true) ||
                    getProperText(contact.organization.company, shouldNormalize).contains(fixedText, true) ||
                    getProperText(contact.organization.jobPosition, shouldNormalize).contains(fixedText, true) ||
                    contact.websites.any { it.contains(fixedText, true) }
            } as ArrayList

            filtered.sortBy {
                val nameToDisplay = it.getNameToDisplay()
                !getProperText(nameToDisplay, shouldNormalize).startsWith(fixedText, true) && !nameToDisplay.contains(fixedText, true)
            }

            activity?.runOnUiThread {
                binding.fragmentPlaceholder.beVisibleIf(filtered.isEmpty())
                (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(filtered, fixedText)
                setupLetterFastScroller(filtered)
            }
        }
    }

    private fun requestReadContactsPermission() {
        activity?.handlePermission(PERMISSION_READ_CONTACTS) {
            if (it) {
                binding.fragmentPlaceholder.text = context.getString(R.string.no_contacts_found)
                binding.fragmentPlaceholder2.text = context.getString(R.string.create_new_contact)
                ContactsHelper(context).getContactsWithSecureBoxFilter { contacts ->
                    activity?.runOnUiThread {
                        gotContacts(contacts)
                    }
                }
            }
        }
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
