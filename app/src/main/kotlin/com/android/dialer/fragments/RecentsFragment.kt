package com.android.dialer.fragments

import android.content.Context
import android.content.Intent
import android.content.Context.MODE_PRIVATE
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.provider.CallLog.Calls
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.dialogs.CallConfirmationDialog
import com.goodwy.commons.extensions.baseConfig
import eightbitlab.com.blurview.BlurTarget
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beGoneIf
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSharedPrefs
import com.goodwy.commons.extensions.getSurfaceColor
import androidx.core.content.ContextCompat
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.launchCallIntent
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.goodwy.commons.extensions.underlineText
import com.goodwy.commons.helpers.CONTACT_ID
import com.goodwy.commons.helpers.ContactsHelper
import com.goodwy.commons.helpers.PERMISSION_READ_CALL_LOG
import com.goodwy.commons.helpers.ensureBackgroundThread
import android.provider.ContactsContract
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.securebox.SecureBoxCall
import com.goodwy.commons.securebox.SecureBoxContact
import com.android.dialer.BuildConfig
import com.android.dialer.R
import com.android.dialer.activities.CallHistoryActivity
import com.android.dialer.activities.MainActivity
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.adapters.RecentCallsAdapter
import com.android.dialer.databinding.FragmentRecentsBinding
import com.android.dialer.extensions.callContactWithSim
import com.android.dialer.extensions.callerNotesHelper
import com.android.dialer.extensions.config
import com.android.dialer.extensions.getContactsWithSecureBoxFilter
import com.android.dialer.extensions.launchSendSMSIntentRecommendation
import com.android.dialer.extensions.numberForNotes
import com.android.dialer.extensions.runAfterAnimations
import com.android.dialer.extensions.startAddContactIntent
import com.android.dialer.extensions.startContactDetailsIntent
import com.android.dialer.helpers.CURRENT_RECENT_CALL
import com.android.dialer.helpers.CURRENT_RECENT_CALL_LIST
import com.android.dialer.helpers.RECENT_CALL_CACHE_SIZE
import com.android.dialer.helpers.RecentsHelper
import com.android.dialer.helpers.QUERY_LIMIT_MAX_VALUE
import com.android.dialer.helpers.SWIPE_ACTION_CALL
import com.android.dialer.helpers.SWIPE_ACTION_MESSAGE
import com.android.dialer.helpers.SWIPE_ACTION_OPEN
import com.android.dialer.interfaces.RefreshItemsListener
import com.android.dialer.models.CallLogItem
import com.android.dialer.models.RecentCall
import com.goodwy.commons.extensions.getIntValue
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.android.dialer.helpers.sharedGson
import com.android.dialer.helpers.RECENT_CALLS_FILTER_TYPE
import com.android.dialer.dialogs.FilterCallTypesDialog
import com.goodwy.commons.views.BlurPopupMenu
import android.view.Gravity

class RecentsFragment(
    context: Context, attributeSet: AttributeSet,
) : MyViewPagerFragment<MyViewPagerFragment.RecentsInnerBinding>(context, attributeSet), RefreshItemsListener {

    private lateinit var binding: FragmentRecentsBinding
    private var allRecentCalls = listOf<CallLogItem>()
    private var recentsAdapter: RecentCallsAdapter? = null

    private var searchQuery: String? = null
    private val recentsHelper = RecentsHelper(context)
    
    // Scroll-based filter bar visibility
    private var isFilterBarVisible = false
    private var lastScrollY = 0
    private var isAnimating = false
    private val scrollThreshold = 15 // Minimum scroll delta to trigger show/hide (prevents flickering)

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecentsBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)
    }

    override fun setupFragment() {
        val useSurfaceColor = context.isDynamicTheme() && !context.isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) context.getSurfaceColor() else context.getProperBackgroundColor()
        binding.recentsFragment.setBackgroundColor(backgroundColor)

        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            R.string.no_previous_calls
        } else {
            R.string.could_not_access_the_call_history
        }

        binding.recentsPlaceholder.text = context.getString(placeholderResId)
        binding.recentsPlaceholder2.apply {
            underlineText()
            setOnClickListener {
                requestCallLogPermission()
            }
        }

        setupFilterBar()
    }

    private fun setupFilterBar() {
        // Ensure default is null (All selected)
        // The Config already defaults to null, but if user had previously set it to MISSED_TYPE
        // and wants "All" as default, we can reset it here
        // For now, we'll respect the user's previous choice, but ensure null is the default
        val currentFilter = context.config.recentCallsFilterType
        val prefs = context.getSharedPrefs()
        if (!prefs.contains(RECENT_CALLS_FILTER_TYPE)) {
            // First time - explicitly set to null to show "All"
            context.config.recentCallsFilterType = null
        }
        
        // Initially show the filter bar - it will hide on scroll down, show on scroll up
        val hasPermission = context.hasPermission(PERMISSION_READ_CALL_LOG)
        updateFilterBar()
        
        if (hasPermission) {
            // Ensure filter bar is visible initially
            binding.recentsFilterBar.apply {
                beVisible()
                alpha = 1f
                translationY = 0f
            }
            isFilterBarVisible = true
        } else {
            // Hide if no permission
            binding.recentsFilterBar.beGone()
            isFilterBarVisible = false
        }
        
        setupScrollListener()
        
        binding.recentsFilterAll.setOnClickListener {
            context.config.recentCallsFilterType = null
            updateFilterBar()
            refreshItems()
        }

        binding.recentsFilterOther.setOnClickListener { view ->
            // If no filter is set, apply the last selected filter type
            if (context.config.recentCallsFilterType == null) {
                val lastFilterType = context.config.recentCallsLastFilterType
                context.config.recentCallsFilterType = lastFilterType
                updateFilterBar()
                refreshItems()
            } else {
                showCallTypePopupMenu(view)
            }
        }
    }

    private fun setupScrollListener() {
        binding.recentsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                
                val hasPermission = context.hasPermission(PERMISSION_READ_CALL_LOG)
                if (!hasPermission) return
                
                val currentScrollY = recyclerView.computeVerticalScrollOffset()
                
                // Always show when at the top of the list
                if (currentScrollY <= 0) {
                    if (!isFilterBarVisible) {
                        showFilterBarAnimated()
                    }
                    lastScrollY = 0
                    return
                }
                
                // Calculate actual scroll position change (more reliable than dy alone)
                val scrollDelta = currentScrollY - lastScrollY
                
                // Only process if there's meaningful scroll movement
                if (Math.abs(scrollDelta) < scrollThreshold && Math.abs(dy) < scrollThreshold) {
                    return
                }
                
                // Use both scrollDelta and dy for more reliable direction detection
                // scrollDelta is based on position (more stable), dy is immediate (more responsive)
                val isScrollingDown = scrollDelta > 0 || (scrollDelta == 0 && dy > 0)
                val isScrollingUp = scrollDelta < 0 || (scrollDelta == 0 && dy < 0)
                
                // Check if we're at the bottom to handle bounce effect
                val range = recyclerView.computeVerticalScrollRange()
                val extent = recyclerView.computeVerticalScrollExtent()
                val maxScrollY = (range - extent).coerceAtLeast(0)
                val isAtBottom = currentScrollY >= maxScrollY - 5
                
                // Handle scroll direction - only trigger if not already animating
                if (!isAnimating) {
                    when {
                        isScrollingDown -> {
                            // Scrolling down - hide filter bar
                            if (isFilterBarVisible) {
                                hideFilterBarAnimated()
                            }
                        }
                        isScrollingUp -> {
                            // Scrolling up - show filter bar
                            // Only show if we're not at bottom (to avoid bounce issues)
                            if (!isFilterBarVisible && !isAtBottom) {
                                showFilterBarAnimated()
                            }
                        }
                    }
                }
                
                lastScrollY = currentScrollY
            }
            
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // When scrolling stops, ensure correct state
                    val currentScrollY = recyclerView.computeVerticalScrollOffset()
                    
                    // Always show at top
                    if (currentScrollY <= 0 && !isFilterBarVisible) {
                        showFilterBarAnimated()
                    }
                    
                    // Reset last scroll position
                    lastScrollY = currentScrollY
                }
            }
        })
    }
    
    private fun showFilterBarAnimated() {
        if (isFilterBarVisible || isAnimating) return
        
        isAnimating = true
        binding.recentsFilterBar.apply {
            // Cancel any ongoing animation
            animate().cancel()
            
            beVisible()
            alpha = 0f
            // Use post to ensure view is measured before animating
            post {
                val height = if (this.height > 0) this.height else 80 // fallback height
                translationY = -height.toFloat()
                
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .setListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            isAnimating = false
                        }
                        override fun onAnimationCancel(animation: android.animation.Animator) {
                            isAnimating = false
                        }
                    })
                    .start()
            }
        }
        isFilterBarVisible = true
    }
    
    private fun hideFilterBarAnimated() {
        if (!isFilterBarVisible || isAnimating) return
        
        isAnimating = true
        binding.recentsFilterBar.apply {
            // Cancel any ongoing animation
            animate().cancel()
            
            animate()
                .alpha(0f)
                .translationY(-height.toFloat())
                .setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .setListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        beGone()
                        translationY = 0f
                        isAnimating = false
                    }
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        isAnimating = false
                    }
                })
                .start()
        }
        isFilterBarVisible = false
    }
    
    private fun updateFilterBar() {
        val filterType = context.config.recentCallsFilterType
        val hasPermission = context.hasPermission(PERMISSION_READ_CALL_LOG)
        
        // Don't change visibility here - it's controlled by scroll
        // binding.recentsFilterBar.beVisibleIf(hasPermission)
        
        if (hasPermission) {
            // Update "Other" chip text - use last selected filter type when filter is null
            val displayType = filterType ?: context.config.recentCallsLastFilterType
            val otherText = when (displayType) {
                Calls.INCOMING_TYPE -> context.getString(R.string.incoming_call)
                Calls.OUTGOING_TYPE -> context.getString(R.string.outgoing_call)
                Calls.MISSED_TYPE -> context.getString(R.string.missed_call)
                else -> context.getString(R.string.missed_call)
            }
            binding.recentsFilterOther.text = otherText
            
            // Update colors with selection state
            val textColor = context.getProperTextColor()
            val primaryColor = context.getProperPrimaryColor()
            val backgroundColor = context.getProperBackgroundColor()
            
            // "All" chip - highlight when filter is null
            if (filterType == null) {
                binding.recentsFilterAll.setChipBackgroundColor(ColorStateList.valueOf(primaryColor.adjustAlpha(0.2f)))
                binding.recentsFilterAll.setTextColor(ColorStateList.valueOf(primaryColor))
            } else {
                binding.recentsFilterAll.setChipBackgroundColor(ColorStateList.valueOf(backgroundColor))
                binding.recentsFilterAll.setTextColor(ColorStateList.valueOf(textColor))
            }
            
            // "Other" chip - highlight when filter is not null
            if (filterType != null) {
                binding.recentsFilterOther.setChipBackgroundColor(ColorStateList.valueOf(primaryColor.adjustAlpha(0.2f)))
                binding.recentsFilterOther.setTextColor(ColorStateList.valueOf(primaryColor))
            } else {
                binding.recentsFilterOther.setChipBackgroundColor(ColorStateList.valueOf(backgroundColor))
                binding.recentsFilterOther.setTextColor(ColorStateList.valueOf(textColor))
            }
        }
    }

    private fun showCallTypePopupMenu(anchorView: View) {
        val menu = BlurPopupMenu(context, anchorView, Gravity.START)
        menu.inflate(R.menu.menu_call_type_filter)
        
        menu.menu.apply {
            findItem(R.id.filter_all).isVisible = true
            findItem(R.id.filter_incoming).isVisible = true
            findItem(R.id.filter_outgoing).isVisible = true
            findItem(R.id.filter_missed).isVisible = true
            
            // Check current selection
            val currentFilter = context.config.recentCallsFilterType
            findItem(R.id.filter_all).isChecked = currentFilter == null
            findItem(R.id.filter_incoming).isChecked = currentFilter == Calls.INCOMING_TYPE
            findItem(R.id.filter_outgoing).isChecked = currentFilter == Calls.OUTGOING_TYPE
            findItem(R.id.filter_missed).isChecked = currentFilter == Calls.MISSED_TYPE
        }
        
        menu.setOnMenuItemClickListener { item ->
            val newFilterType = when (item.itemId) {
                R.id.filter_all -> null
                R.id.filter_incoming -> Calls.INCOMING_TYPE
                R.id.filter_outgoing -> Calls.OUTGOING_TYPE
                R.id.filter_missed -> Calls.MISSED_TYPE
                else -> null
            }
            
            if (context.config.recentCallsFilterType != newFilterType) {
                context.config.recentCallsFilterType = newFilterType
                updateFilterBar()
                refreshItems()
            }
            true
        }
        
        menu.show()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, accentColor: Int) {
        binding.recentsPlaceholder.setTextColor(textColor)
        binding.recentsPlaceholder2.setTextColor(primaryColor)

        // Update filter bar chips colors
        updateFilterBarColors(textColor, primaryColor)

        recentsAdapter?.apply {
            updatePrimaryColor()
            updateBackgroundColor(context.getProperBackgroundColor())
            updateTextColor(textColor)
            initDrawables(textColor)
        }
    }

    private fun updateFilterBarColors(textColor: Int, primaryColor: Int) {
        binding.recentsFilterAll.setTextColor(textColor)
        binding.recentsFilterOther.setTextColor(textColor)
        // Chips will handle their own background colors based on checked state
    }

    override fun refreshItems(invalidate: Boolean, needUpdate: Boolean, callback: (() -> Unit)?) {
        if (invalidate) {
            allRecentCalls = emptyList()
            activity!!.config.recentCallsCache = ""
        }

        if (needUpdate || !searchQuery.isNullOrEmpty() || activity!!.config.needUpdateRecents) {
            // When needUpdate is true (e.g., contact deleted), refresh immediately without waiting for animations
            if (needUpdate) {
                refreshCallLog(loadAll = true)
            } else {
                refreshCallLog(loadAll = false) {
                    binding.recentsList.runAfterAnimations {
                        refreshCallLog(loadAll = true)
                    }
                }
            }
        } else {
            var recents = emptyList<RecentCall>()
            if (!invalidate) {
                try {
                    recents = activity!!.config.parseRecentCallsCache()
                } catch (_: Exception) {
                    activity!!.config.recentCallsCache = ""
                }
            }

            if (recents.isNotEmpty()) {
                refreshCallLogFromCache(recents) {
                    binding.recentsList.runAfterAnimations {
                        refreshCallLog(loadAll = true)
                    }
                }
            } else {
                refreshCallLog(loadAll = false) {
                    binding.recentsList.runAfterAnimations {
                        refreshCallLog(loadAll = true)
                    }
                }
            }
        }
    }

    override fun onSearchClosed() {
        searchQuery = null
        val filteredCalls = applyCallTypeFilter(allRecentCalls.filterIsInstance<RecentCall>())
        showOrHidePlaceholder(filteredCalls.isEmpty())
        recentsAdapter?.updateItems(filteredCalls)
    }

    override fun onSearchQueryChanged(text: String) {
        searchQuery = text
        updateSearchResult()
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateSearchResult() {
        ensureBackgroundThread {
            val fixedText = searchQuery!!.trim().replace("\\s+".toRegex(), " ")
            val recentCalls = allRecentCalls
                .filterIsInstance<RecentCall>()
                .filter {
                    it.name.contains(fixedText, true) ||
                        it.doesContainPhoneNumber(fixedText) ||
                        it.nickname.contains(fixedText, true) ||
                        it.company.contains(fixedText, true) ||
                        it.jobPosition.contains(fixedText, true)
                }
                .sortedWith(
                    compareByDescending<RecentCall> { it.dayCode }
                        .thenByDescending { it.name.startsWith(fixedText, true) }
                        .thenByDescending { it.startTS }
                )

            val filteredCalls = applyCallTypeFilter(recentCalls)
            prepareCallLog(filteredCalls) {
                activity?.runOnUiThread {
                    showOrHidePlaceholder(filteredCalls.isEmpty())
                    recentsAdapter?.updateItems(it, fixedText)
                }
            }
        }
    }

    private fun requestCallLogPermission() {
        activity?.handlePermission(PERMISSION_READ_CALL_LOG) {
            if (it) {
                binding.recentsPlaceholder.text = context.getString(R.string.no_previous_calls)
                binding.recentsPlaceholder2.beGone()
                // Show filter bar when permission is granted
                binding.recentsFilterBar.apply {
                    beVisible()
                    alpha = 1f
                    translationY = 0f
                }
                isFilterBarVisible = true
                updateFilterBar()
                refreshCallLog()
            }
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        if (show /*&& !binding.progressIndicator.isVisible()*/) {
            binding.recentsPlaceholder.beVisible()
        } else {
            binding.recentsPlaceholder.beGone()
        }
    }

    fun showCallTypeFilterDialog() {
        val blurTarget = activity?.findViewById<BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        val recentCalls = allRecentCalls.filterIsInstance<RecentCall>()
        FilterCallTypesDialog(activity as SimpleActivity, blurTarget, recentCalls) {
            refreshItems()
        }
    }

    private fun applyCallTypeFilter(calls: List<RecentCall>): List<RecentCall> {
        val filterType = context.config.recentCallsFilterType
        return if (filterType == null) {
            calls
        } else {
            calls.filter { it.type == filterType }
        }
    }

    private fun gotRecents(recents: List<CallLogItem>) {
//        binding.progressIndicator.hide()
        val recentCalls = recents.filterIsInstance<RecentCall>()
        val filteredRecents = applyCallTypeFilter(recentCalls)
        val filteredRecentsAsItems: List<CallLogItem> = filteredRecents
        if (filteredRecentsAsItems.isEmpty()) {
            binding.apply {
                showOrHidePlaceholder(true)
                recentsPlaceholder2.beGoneIf(context.hasPermission(PERMISSION_READ_CALL_LOG))
                recentsList.beGone()
            }
        } else {
                binding.apply {
                showOrHidePlaceholder(false)
                recentsPlaceholder2.beGone()
                recentsList.beVisible()

                // Optimize RecyclerView for large lists
                recentsList.setHasFixedSize(true)
                if (filteredRecents.size > 2000) {
                    recentsList.itemAnimator = null
                }

                recentsList.setOnTouchListener { _, _ ->
                    activity?.hideKeyboard()
                    false
                }
            }

            if (binding.recentsList.adapter == null) {
                recentsAdapter = RecentCallsAdapter(
                    activity = activity as SimpleActivity,
                    recyclerView = binding.recentsList,
                    refreshItemsListener = this,
                    showOverflowMenu = true,
                    showCallIcon = context.config.onRecentClick == SWIPE_ACTION_OPEN,
                    hideTimeAtOtherDays = true,
                    itemDelete = { deleted ->
                        allRecentCalls = allRecentCalls.filter { it !in deleted }
                    },
                    itemClick = {
                        itemClickAction(context.config.onRecentClick, it as RecentCall)
                    },
                    profileInfoClick = { recentCall ->
                        actionOpen(recentCall)
                    },
                    profileIconClick = {
                        val recentCall = it as RecentCall
                        findContactByCall(recentCall) { contact ->
                            activity?.runOnUiThread {
                                if (contact != null) {
                                    activity?.startContactDetailsIntent(contact)
                                } else {
                                    activity?.startAddContactIntent(recentCall.phoneNumber)
                                }
                            }
                        }
                    }
                )

                recentsAdapter?.addBottomPadding(64)

                binding.recentsList.adapter = recentsAdapter
                recentsAdapter?.updateItems(filteredRecentsAsItems)
            } else {
                recentsAdapter?.updateItems(filteredRecentsAsItems)
            }
        }
    }

    private fun callRecentNumber(recentCall: RecentCall) {
        if (context.config.callUsingSameSim && recentCall.simID > 0) {
            val sim = recentCall.simID == 1
            activity?.callContactWithSim(recentCall.phoneNumber, sim)
        }
        else {
            activity?.launchCallIntent(recentCall.phoneNumber, key = BuildConfig.RIGHT_APP_KEY)
        }
    }

    private fun refreshCallLog(loadAll: Boolean = false, callback: (() -> Unit)? = null) {
        getRecentCalls(loadAll) {
            allRecentCalls = it
            if (searchQuery.isNullOrEmpty()) {
                activity?.runOnUiThread { gotRecents(it) }
                callback?.invoke()

                context.config.recentCallsCache = sharedGson.toJson(it.take(RECENT_CALL_CACHE_SIZE))
            } else {
                updateSearchResult()
                callback?.invoke()
            }

            //Deleting notes if a call has already been deleted
            context.callerNotesHelper.removeCallerNotes(
                it.map { recentCall -> recentCall.phoneNumber.numberForNotes()}
            )
        }

        if (loadAll) {
            with(recentsHelper) {
                val queryCount = context.config.queryLimitRecent
                getRecentCalls(queryLimit = queryCount, updateCallsCache = false) { it ->
                    ensureBackgroundThread {
                        val recentOutgoingNumbers = it
                            .filter { it.type == Calls.OUTGOING_TYPE }
                            .map { recentCall -> recentCall.phoneNumber }

                        context.config.recentOutgoingNumbers = recentOutgoingNumbers.toMutableSet()
                    }
                }
            }
        }
    }

    private fun refreshCallLogFromCache(cache: List<RecentCall>, callback: (() -> Unit)? = null) {
        val filteredCache = applyCallTypeFilter(cache)
        val filteredCacheAsItems: List<CallLogItem> = filteredCache
        gotRecents(filteredCacheAsItems)
        callback?.invoke()
    }

    private fun getRecentCalls(loadAll: Boolean, callback: (List<RecentCall>) -> Unit) {
        val queryCount = if (loadAll) context.config.queryLimitRecent else RecentsHelper.QUERY_LIMIT
        val existingRecentCalls = allRecentCalls.filterIsInstance<RecentCall>()

        with(recentsHelper) {
            if (context.config.groupSubsequentCalls) {
                getGroupedRecentCalls(existingRecentCalls, queryCount) {
                    prepareCallLog(it, callback)
                }
            } else {
                getRecentCalls(existingRecentCalls, queryCount, updateCallsCache = true) { it ->
                    val calls = if (context.config.groupAllCalls) it.distinctBy { it.phoneNumber } else it
                    prepareCallLog(calls, callback)
                }
            }
        }
    }

    private fun prepareCallLog(calls: List<RecentCall>, callback: (List<RecentCall>) -> Unit) {
        if (calls.isEmpty()) {
            callback(emptyList())
            return
        }

        ContactsHelper(context).getContactsWithSecureBoxFilter(showOnlyContactsWithNumbers = true) { contacts ->
            ensureBackgroundThread {
                val updatedCalls = updateNamesIfEmpty(
                    calls = calls,
                    contacts = contacts
                )

                callback(
                    updatedCalls
                )
            }
        }
    }

    private fun updateNamesIfEmpty(calls: List<RecentCall>, contacts: List<Contact>): List<RecentCall> {
        if (calls.isEmpty()) return mutableListOf()

        // Create a map for O(1) contact lookups instead of O(n) linear search
        val contactsWithNumbers = contacts.filter { it.phoneNumbers.isNotEmpty() }
        val phoneNumberToContact = HashMap<String, Contact>(contactsWithNumbers.size)
        
        contactsWithNumbers.forEach { contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                phoneNumberToContact[phoneNumber.normalizedNumber] = contact
            }
        }

        return calls.map { call ->
            val normalizedNumber = call.phoneNumber.normalizePhoneNumber()
            val contact = phoneNumberToContact[normalizedNumber]
            
            if (contact != null) {
                // Contact exists, update name if needed
                if (call.phoneNumber == call.name || call.name != contact.getNameToDisplay()) {
                    withUpdatedName(call = call, name = contact.getNameToDisplay())
                } else {
                    call
                }
            } else {
                // Contact doesn't exist (was deleted), revert to phone number
                if (call.phoneNumber != call.name) {
                    withUpdatedName(call = call, name = call.phoneNumber)
                } else {
                    call
                }
            }
        }
    }

    private fun withUpdatedName(call: RecentCall, name: String): RecentCall {
        return call.copy(
            name = name,
            groupedCalls = call.groupedCalls
                ?.map { it.copy(name = name) }
                ?.toMutableList()
                ?.ifEmpty { null }
        )
    }

//    private fun groupCallsByDate(recentCalls: List<RecentCall>): MutableList<CallLogItem> {
//        val callLog = mutableListOf<CallLogItem>()
//        var lastDayCode = ""
//        for (call in recentCalls) {
//            val currentDayCode = call.dayCode
//            if (currentDayCode != lastDayCode) {
//                callLog += CallLogItem.Date(timestamp = call.startTS, dayCode = currentDayCode)
//                lastDayCode = currentDayCode
//            }
//
//            callLog += call
//        }
//
//        return callLog
//    }

    private fun findContactByCall(recentCall: RecentCall, callback: (Contact?) -> Unit) {
        // Use PhoneLookup for fast single contact lookup instead of loading all contacts
        // This is much more efficient than loading 900+ contacts just to find one
        ensureBackgroundThread {
            if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
                callback(null)
                return@ensureBackgroundThread
            }
            
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon()
                .appendPath(android.net.Uri.encode(recentCall.phoneNumber))
                .build()
            
            val projection = arrayOf(ContactsContract.PhoneLookup._ID)
            
            var foundContact: Contact? = null
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        // PhoneLookup returns CONTACT_ID, but we need RAW_CONTACT_ID for getContactWithId
                        // Query for raw contact ID using the phone number
                        val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                        val phoneProjection = arrayOf(ContactsContract.Data.RAW_CONTACT_ID)
                        val phoneSelection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ? OR ${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} = ?"
                        val normalizedNumber = recentCall.phoneNumber.normalizePhoneNumber()
                        val phoneSelectionArgs = arrayOf(recentCall.phoneNumber, normalizedNumber)
                        
                        context.contentResolver.query(phoneUri, phoneProjection, phoneSelection, phoneSelectionArgs, null)?.use { phoneCursor ->
                            if (phoneCursor.moveToFirst()) {
                                val rawContactId = phoneCursor.getIntValue(ContactsContract.Data.RAW_CONTACT_ID)
                                val contactHelper = ContactsHelper(context)
                                foundContact = contactHelper.getContactWithId(rawContactId)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            
            callback(foundContact)
        }
    }

    override fun myRecyclerView() = binding.recentsList

    /**
     * Show secure box contents filtered by cipher number
     */
    fun showSecureBoxByCipherNumber(calls: List<SecureBoxCall>, contacts: List<SecureBoxContact>, cipherNumber: Int) {
        ensureBackgroundThread {
            // Get recent calls that match the secure box call IDs
            val secureBoxCallIds = calls.map { it.callId }.toSet()
            
            // Get all recent calls and filter by secure box call IDs
            recentsHelper.getRecentCalls(queryLimit = QUERY_LIMIT_MAX_VALUE) { recentCallsFromHelper ->
                val secureBoxRecentCalls = recentCallsFromHelper.filter { it.id in secureBoxCallIds }
                
                prepareCallLog(secureBoxRecentCalls) { filteredCalls ->
                    activity?.runOnUiThread {
                        allRecentCalls = filteredCalls
                        binding.recentsPlaceholder.text = "Secure Box Cipher $cipherNumber"
                        gotRecents(filteredCalls)
                    }
                }
            }
        }
    }

    private fun itemClickAction(action: Int, call: RecentCall) {
        when (action) {
            SWIPE_ACTION_MESSAGE -> actionSMS(call)
            SWIPE_ACTION_CALL -> actionCall(call)
            SWIPE_ACTION_OPEN -> actionOpen(call)
            else -> {}
        }
    }

    private fun actionCall(call: RecentCall) {
        val recentCall = call
        if (context.config.showCallConfirmation) {
            val blurTarget = activity?.findViewById<BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            CallConfirmationDialog(activity as SimpleActivity, recentCall.name, blurTarget = blurTarget) {
                callRecentNumber(recentCall)
            }
        } else {
            callRecentNumber(recentCall)
        }
    }

    private fun actionSMS(call: RecentCall) {
        activity?.launchSendSMSIntentRecommendation(call.phoneNumber)
    }

    private fun actionOpen(call: RecentCall) {
        val recentCalls = call.groupedCalls as ArrayList<RecentCall>? ?: arrayListOf(call)
        // Use contactID from call if available, otherwise load contact
        val contactId = call.contactID
        if (contactId != null && contactId > 0) {
            // Use contactID directly if available
            Intent(activity, CallHistoryActivity::class.java).apply {
                putExtra(CURRENT_RECENT_CALL, call)
                putExtra(CURRENT_RECENT_CALL_LIST, recentCalls)
                putExtra(CONTACT_ID, contactId)
                activity?.launchActivityIntent(this)
            }
        } else {
            // Fallback: load contact by phone number
            findContactByCall(call) { contact ->
                activity?.runOnUiThread {
                    Intent(activity, CallHistoryActivity::class.java).apply {
                        putExtra(CURRENT_RECENT_CALL, call)
                        putExtra(CURRENT_RECENT_CALL_LIST, recentCalls)
                        putExtra(CONTACT_ID, contact?.id)
                        activity?.launchActivityIntent(this)
                    }
                }
            }
        }
    }
}

class BottomSpaceDecoration(private val spaceHeight: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == parent.adapter?.itemCount?.minus(1) ?: 0) {
            outRect.bottom = spaceHeight
        }
    }
}
