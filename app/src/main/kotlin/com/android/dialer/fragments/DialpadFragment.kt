package com.android.dialer.fragments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import com.goodwy.commons.views.CustomPhoneNumberFormattingTextWatcher
import android.telephony.TelephonyManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.behaviorule.arturdumchev.library.pixels
import com.behaviorule.arturdumchev.library.setHeight
import com.goodwy.commons.helpers.NavigationIcon
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.views.MyRecyclerView
import com.android.dialer.BuildConfig
import com.android.dialer.R
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.adapters.ContactsAdapter
import com.android.dialer.adapters.RecentCallsAdapter
import com.android.dialer.databinding.DialpadGridBinding
import com.android.dialer.databinding.FragmentDialpadBinding
import com.android.dialer.helpers.CallerNotesHelper
import com.android.dialer.extensions.*
import com.android.dialer.helpers.*
import com.android.dialer.interfaces.RefreshItemsListener
import com.android.dialer.models.RecentCall
import com.android.dialer.models.SpeedDial
import com.android.dialer.activities.CallHistoryActivity
import com.android.dialer.activities.ManageSpeedDialActivity
import com.goodwy.commons.dialogs.CallConfirmationDialog
import com.goodwy.commons.dialogs.ConfirmationAdvancedDialog
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.android.dialer.helpers.sharedGson
import com.mikhaellopez.rxanimation.RxAnimation
import com.mikhaellopez.rxanimation.shake
import eightbitlab.com.blurview.BlurTarget
import me.grantland.widget.AutofitHelper
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import com.android.dialer.models.Events
import java.io.InputStreamReader
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DialpadFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.DialpadInnerBinding>(context, attributeSet), RefreshItemsListener {
    private lateinit var binding: FragmentDialpadBinding
    private var dialpadGridBinding: DialpadGridBinding? = null

    var allContacts = mutableListOf<Contact>()
    private var speedDialValues = mutableListOf<SpeedDial>()
    private var toneGeneratorHelper: ToneGeneratorHelper? = null
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val pressedKeys = mutableSetOf<Char>()
    private var hasBeenScrolled = false
    private var storedDialpadStyle = 0
    private var storedBackgroundColor = 0
    private var storedToneVolume = 0
    private var storedDialpadSize = 0
    private var storedCallButtonPrimarySize = 0
    private var storedCallButtonSecondarySize = 0
    private var storedDialpadBottomMargin = 0
    private var storedHideDialpadNumbers = false
    private var storedHideDialpadLetters = false
    private var storedDialpadSecondaryLanguage: String? = null
    private var storedDialpadSecondaryTypeface = 0
    private var storedShowVoicemailIcon = false
    private var storedFormatPhoneNumbers = false
    private var phoneNumberFormattingWatcher: CustomPhoneNumberFormattingTextWatcher? = null
    private var allRecentCalls = listOf<RecentCall>()
    private var recentsAdapter: RecentCallsAdapter? = null
    private var contactsAdapter: ContactsAdapter? = null
    private val recentsHelper = RecentsHelper(context)
    private val callerNotesHelper = CallerNotesHelper(context)
    private var isTalkBackOn = false
    private var initSearch = true
    private val fragmentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var contactObserver: ContentObserver? = null
    private var isResumed = false
    
    // Cached values for performance
    private var cachedDialpadView: View? = null
    private var cachedDialpadStyle: Int = -1
    private var cachedLanguage: String? = null
    private var cachedCollator: Collator? = null
    private var searchDebounceJob: kotlinx.coroutines.Job? = null
    
    // Cache config and colors to avoid repeated lookups
    private val config get() = context.config
    private var cachedIsDynamicTheme: Boolean? = null
    private var cachedIsSystemInDarkMode: Boolean? = null
    private var cachedProperBackgroundColor: Int? = null
    private var cachedSurfaceColor: Int? = null
    private var cachedAppbarBackgroundColor: Int? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentDialpadBinding.bind(this)

        val dialpadClearWrapper = binding.dialpadClearWrapper.root
        dialpadGridBinding = DialpadGridBinding.bind(dialpadClearWrapper)

        innerBinding = MyViewPagerFragment.DialpadInnerBinding(this)
    }

    override fun setupFragment() {
        EventBus.getDefault().register(this)
        activity?.setupEdgeToEdge(
            padBottomSystem = listOf(
                binding.dialpadClearWrapper.dialpadGridLayout,
                binding.dialpadRoundWrapper.dialpadIosWrapper,
                binding.dialpadRectWrapper.dialpadGridWrapper
            ),
            moveTopSystem = listOf(binding.dialpadInput),
            moveBottomSystem = listOf(binding.dialpadRoundWrapperUp),
        )
        
        // Cache theme and color values
        updateCachedThemeValues()
        val backgroundColor = getCachedBackgroundColor()
        setBackgroundColor(backgroundColor)

        // Initialize T9 if needed
        if (!DialpadT9.Initialized) {
            val reader = InputStreamReader(context.resources.openRawResource(R.raw.t9languages))
            DialpadT9.readFromJson(reader.readText())
        }

        speedDialValues = config.getSpeedDialValues()

        toneGeneratorHelper = ToneGeneratorHelper(context, DIALPAD_TONE_LENGTH_MS)
        isTalkBackOn = context.isTalkBackOn()

        binding.dialpadInput.apply {
            // Disable keyboard first to prevent IME from showing on focus
            disableKeyboard()
            if (config.formatPhoneNumbers) {
                phoneNumberFormattingWatcher = CustomPhoneNumberFormattingTextWatcher()
                addTextChangedListener(phoneNumberFormattingWatcher)
            }
            onTextChangeListener { dialpadValueChanged(it) }
            AutofitHelper.create(this)
            // Always visible since it's positioned like a toolbar
            beVisible()
            // Clear focus to prevent auto-focus on startup
            clearFocus()
        }

        // Optimize: Use loadExtendedFields=false for faster dialpad contact loading
        // Dialpad only needs basic contact info for T9 search
        ContactsHelper(context).getContactsWithSecureBoxFilter(
            showOnlyContactsWithNumbers = true,
            loadExtendedFields = false
        ) { contacts ->
            gotContacts(contacts)
        }
        // Initialize stored values - use -1 for storedDialpadStyle to ensure first style change is detected
        if (storedDialpadStyle == 0) {
            storedDialpadStyle = -1 // Force style change detection on first setup
        }
        storedToneVolume = config.toneVolume
        storedBackgroundColor = cachedProperBackgroundColor ?: context.getProperBackgroundColor()
        storedDialpadSize = config.dialpadSize
        storedCallButtonPrimarySize = config.callButtonPrimarySize
        storedCallButtonSecondarySize = config.callButtonSecondarySize
        storedDialpadBottomMargin = config.dialpadBottomMargin
        storedHideDialpadNumbers = config.hideDialpadNumbers
        storedHideDialpadLetters = config.hideDialpadLetters
        storedDialpadSecondaryLanguage = config.dialpadSecondaryLanguage
        storedDialpadSecondaryTypeface = config.dialpadSecondaryTypeface
        storedShowVoicemailIcon = config.showVoicemailIcon
        storedFormatPhoneNumbers = config.formatPhoneNumbers

        // Handle hideDialpadNumbers
        if (config.hideDialpadNumbers) {
            handleHideDialpadNumbers()
        }

        // Initialize dialpad style
        initStyle()
        storedDialpadStyle = config.dialpadStyle // Update stored style after initialization

        // Update dialpad size
        updateDialpadSize()

        // Update call button size if needed
        val dialpadStyle = config.dialpadStyle
        if (dialpadStyle == DIALPAD_GRID || dialpadStyle == DIALPAD_ORIGINAL) {
            updateCallButtonSize()
        }

        // Initialize placeholder for recent calls
        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            R.string.no_previous_calls
        } else {
            R.string.could_not_access_the_call_history
        }
        binding.dialpadRecentsPlaceholder.text = context.getString(placeholderResId)
        binding.dialpadRecentsPlaceholder.beGone()

        // Load recent calls if enabled
        if (config.showRecentCallsOnDialpad) {
            refreshItems()
        }

        setupDialpadButtons()
        setupCallAndClearButtons()
        setupAddNumberButton()
        setupScrollListeners()

        // Setup toolbar and appbar - use cached appbar background color
        val appbarBackgroundColor = getCachedAppbarBackgroundColor()
        activity?.let { act ->
            act.setupTopAppBar(
                topAppBar = binding.dialpadAppbar,
                navigationIcon = NavigationIcon.None,
                topBarColor = appbarBackgroundColor,
                navigationClick = false
            )
        }
        // Explicitly set toolbar background color to match appbar
        binding.dialpadToolbar.setBackgroundColor(appbarBackgroundColor)
        setupOptionsMenu()


        // Setup dialpadRoundWrapperUp click listener
        binding.dialpadRoundWrapperUp.setOnClickListener { dialpadHide() }

        // Ensure dialpad is visible initially
        val view = dialpadView()
        view?.apply {
            visibility = View.VISIBLE
            translationY = 0f
            // Post to ensure layout is complete, especially for iOS style
            post {
                visibility = View.VISIBLE
                translationY = 0f
            }
        }

        // Setup input focus and click listeners
        binding.dialpadInput.setOnClickListener {
            val currentView = dialpadView()
            if (currentView?.isGone == true) dialpadHide()
        }
        binding.dialpadInput.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val currentView = dialpadView()
                if (currentView?.isGone == true) dialpadHide()
            }
        }

        // Note: Recent calls list visibility will be updated in gotRecents() when recents are loaded
        // and in dialpadValueChanged() when input changes
    }

    private fun setupScrollListeners() {
        val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (!hasBeenScrolled) {
                    hasBeenScrolled = true
                    dialpadView()?.let { slideDown(it) }
                }
            }
        }
        binding.dialpadList.addOnScrollListener(scrollListener)
        binding.dialpadRecentsList.addOnScrollListener(scrollListener)
    }

    private fun setupDialpadButtons() {
        // Only setup for GRID and ORIGINAL styles (others would need their own setup)
        // Note: initLetters() also sets up these buttons, but we keep this method
        // for initial setup in setupFragment() before initStyle() is called
        if (context.config.dialpadStyle == DIALPAD_GRID || context.config.dialpadStyle == DIALPAD_ORIGINAL) {
            dialpadGridBinding?.apply {
                setupCharClick(dialpad1Holder, '1')
                setupCharClick(dialpad2Holder, '2')
                setupCharClick(dialpad3Holder, '3')
                setupCharClick(dialpad4Holder, '4')
                setupCharClick(dialpad5Holder, '5')
                setupCharClick(dialpad6Holder, '6')
                setupCharClick(dialpad7Holder, '7')
                setupCharClick(dialpad8Holder, '8')
                setupCharClick(dialpad9Holder, '9')
                setupCharClick(dialpad0Holder, '0')
                setupCharClick(dialpadAsteriskHolder, '*')
                setupCharClick(dialpadHashtagHolder, '#')
            }
        }
    }

    private fun setupCallAndClearButtons() {
        // For GRID and ORIGINAL styles, call/clear buttons are set up in initLetters()
        // This method is kept for backward compatibility
        // initLetters() already sets up all the buttons, so this is essentially a no-op
    }

    private fun setupAddNumberButton() {
        binding.dialpadAddNumber.setOnClickListener {
            addNumberToContact()
        }
    }

    private fun addNumberToContact() {
        val number = binding.dialpadInput.value
        if (number.isEmpty()) return
        activity?.startAddContactIntent(number)
    }

    private fun clearChar(view: View?) {
        binding.dialpadInput.dispatchKeyEvent(binding.dialpadInput.getKeyEvent(KeyEvent.KEYCODE_DEL))
        maybePerformDialpadHapticFeedback(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCharClick(view: View?, char: Char, longClickable: Boolean = true) {
        view ?: return
        view.isClickable = true
        view.isLongClickable = true
        if (isTalkBackOn) {
            view.setOnClickListener {
                startDialpadTone(char)
                dialpadPressed(char, view)
                stopDialpadTone(char)
            }
            view.setOnLongClickListener { performLongClick(view, char); true }
        } else view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dialpadPressed(char, view)
                    startDialpadTone(char)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                        longPressHandler.postDelayed({
                            performLongClick(view, char)
                        }, longPressTimeout)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopDialpadTone(char)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val viewContainsTouchEvent = if (event.rawX.isNaN() || event.rawY.isNaN()) {
                        false
                    } else {
                        view.boundingBox.contains(event.rawX.roundToInt(), event.rawY.roundToInt())
                    }

                    if (!viewContainsTouchEvent) {
                        stopDialpadTone(char)
                        if (longClickable) {
                            longPressHandler.removeCallbacksAndMessages(null)
                        }
                    }
                }
            }
            false
        }
    }

    private fun dialpadPressed(char: Char, view: View?) {
        binding.dialpadInput.addCharacter(char)
        maybePerformDialpadHapticFeedback(view)
    }

    private fun startDialpadTone(char: Char) {
        if (config.dialpadBeeps) {
            pressedKeys.add(char)
            toneGeneratorHelper?.startTone(char)
        }
    }

    private fun stopDialpadTone(char: Char) {
        if (config.dialpadBeeps) {
            if (!pressedKeys.remove(char)) return
            if (pressedKeys.isEmpty()) {
                toneGeneratorHelper?.stopTone()
            } else {
                startDialpadTone(pressedKeys.last())
            }
        }
    }

    private fun maybePerformDialpadHapticFeedback(view: View?) {
        if (config.dialpadVibration) {
            view?.performHapticFeedback()
        }
    }

    private fun performLongClick(view: View, char: Char) {
        if (char == '0') {
            clearChar(view)
            dialpadPressed('+', view)
        } else if (char == '*') {
            clearChar(view)
            dialpadPressed(',', view)
        } else if (char == '#') {
            clearChar(view)
            when (config.dialpadHashtagLongClick) {
                DIALPAD_LONG_CLICK_WAIT -> dialpadPressed(';', view)
                DIALPAD_LONG_CLICK_SETTINGS_DIALPAD -> {
                    activity?.let { act ->
                        act.startActivity(Intent(act.applicationContext, com.android.dialer.activities.SettingsDialpadActivity::class.java))
                    }
                }

                else -> {
                    activity?.let { act ->
                        act.startActivity(Intent(act.applicationContext, com.android.dialer.activities.SettingsActivity::class.java))
                    }
                }
            }
        } else {
            val result = speedDial(char.digitToInt())
            if (result) {
                stopDialpadTone(char)
                clearChar(view)
            }
        }
    }

    private fun speedDial(id: Int): Boolean {
        if (binding.dialpadInput.value.length == 1) {
            val speedDial = speedDialValues.firstOrNull { it.id == id }
            if (speedDial?.isValid() == true) {
                initCall(speedDial.number, -1, speedDial.getName(context))
                return true
            } else {
                val currentActivity = activity ?: return false
                val blurTarget = currentActivity.findViewById<BlurTarget>(R.id.mainBlurTarget)
                if (blurTarget != null) {
                    ConfirmationDialog(currentActivity, context.getString(R.string.open_speed_dial_manage), blurTarget = blurTarget) {
                        currentActivity.startActivity(Intent(currentActivity.applicationContext, ManageSpeedDialActivity::class.java))
                    }
                } else {
                    // Fallback when blurTarget is not available
                    context.toast(R.string.open_speed_dial_manage)
                    fragmentScope.launch {
                        delay(500)
                        currentActivity.startActivity(Intent(currentActivity.applicationContext, ManageSpeedDialActivity::class.java))
                    }
                }
            }
        }
        return false
    }

    private fun dialpadValueChanged(textFormat: String) {
        val len = textFormat.length
        val view = dialpadView()
        if (len == 0 && view?.isGone == true) {
            slideUp(view)
            // When input is cleared, refresh visibility to show placeholder if needed
            if (context.config.showRecentCallsOnDialpad && allRecentCalls.isEmpty()) {
                binding.dialpadRecentsPlaceholder.beVisible()
                binding.dialpadRecentsList.beGone()
            }
        }
        //Only works for system apps, CALL_PRIVILEGED and MODIFY_PHONE_STATE permissions are required
        if (len > 2 && textFormat.startsWith("*#*#") && textFormat.endsWith("#*#*")) {
            val secretCode = textFormat.substring(4, textFormat.length - 4)
            if (context.isDefaultDialer()) {
                context.getSystemService(TelephonyManager::class.java)?.sendDialerSpecialCode(secretCode)
            } else {
                // Note: launchSetDefaultDialerIntent is protected, so we can't call it from fragment
                // The activity should handle this if needed
//                context.toast(R.string.set_as_default_dialer)
                activity?.launchSetDefaultDialerIntent()
            }
            return
        }

        // Finish any active selection modes
        (binding.dialpadList.adapter as? ContactsAdapter)?.finishActMode()
        (binding.dialpadRecentsList.adapter as? RecentCallsAdapter)?.finishActMode()

        val text = if (config.formatPhoneNumbers) textFormat.removeNumberFormatting() else textFormat

        // Cancel previous debounce job
        searchDebounceJob?.cancel()
        
        // Debounce search for better performance
        searchDebounceJob = fragmentScope.launch {
            delay(150) // Wait 150ms before processing
            performContactSearch(text)
        }
    }
    
    private fun performContactSearch(text: String) {
        // Cache collator and language settings
        if (cachedCollator == null) {
            cachedCollator = Collator.getInstance(context.sysLocale())
        }
        val collator = cachedCollator!!
        
        val langPref = config.dialpadSecondaryLanguage ?: ""
        val langLocale = Locale.getDefault().language
        val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
        val lang = if (isAutoLang) langLocale else langPref
        
        // Only recalculate language-dependent conversions if language changed
        cachedLanguage = lang
        
        // When text is empty, show all recent calls (no filtering needed)
        if (text.isEmpty()) {
            updateSearchResults(ArrayList(), allRecentCalls, text)
            return
        }
        
        // Pre-compute textLower once
        val textLower = text.lowercase()
        val textLowerNoSpaces = textLower.filterNot { it.isWhitespace() }
        
        val filtered = allContacts.filter { contact ->
            // Check phone number first (fastest check)
            if (contact.doesContainPhoneNumber(text, convertLetters = true, search = true)) {
                return@filter true
            }
            
            // Do string conversions - optimize by reusing normalized strings
            val nameNormalized = contact.name.normalizeString().uppercase()
            val convertedName = DialpadT9.convertLettersToNumbers(nameNormalized, lang)
            if (convertedName.contains(textLower, ignoreCase = true) || 
                convertedName.filterNot { it.isWhitespace() }.contains(textLowerNoSpaces, ignoreCase = true)) {
                return@filter true
            }
            
            val nameToDisplayNormalized = contact.getNameToDisplay().normalizeString().uppercase()
            val convertedNameToDisplay = DialpadT9.convertLettersToNumbers(nameToDisplayNormalized, lang)
            if (convertedNameToDisplay.contains(textLower, ignoreCase = true) ||
                convertedNameToDisplay.filterNot { it.isWhitespace() }.contains(textLowerNoSpaces, ignoreCase = true)) {
                return@filter true
            }
            
            val nicknameNormalized = contact.nickname.normalizeString().uppercase()
            val convertedNickname = DialpadT9.convertLettersToNumbers(nicknameNormalized, lang)
            if (convertedNickname.contains(textLower, ignoreCase = true)) return@filter true
            
            val companyNormalized = contact.organization.company.normalizeString().uppercase()
            val convertedCompany = DialpadT9.convertLettersToNumbers(companyNormalized, lang)
            convertedCompany.contains(textLower, ignoreCase = true)
        }.sortedWith(compareBy(collator) {
            it.getNameToDisplay()
        }).toMutableList() as ArrayList<Contact>

        // Filter recent calls
        val filteredRecents = allRecentCalls
            .filter {
                it.name.contains(text, true) ||
                    it.doesContainPhoneNumber(text) ||
                    it.nickname.contains(text, true) ||
                    it.company.contains(text, true) ||
                    it.jobPosition.contains(text, true)
            }
            .sortedWith(
                compareByDescending<RecentCall> { it.dayCode }
                    .thenByDescending { it.name.startsWith(text, true) }
                    .thenByDescending { it.startTS }
            )
        
        updateSearchResults(filtered, filteredRecents, text)
    }
    
    private fun updateSearchResults(filtered: ArrayList<Contact>, filteredRecents: List<RecentCall>, text: String) {
        val currentActivity = activity
        if (currentActivity == null) {
            // Clear adapters if activity is null
            contactsAdapter = null
            return
        }
        
        val currAdapter = binding.dialpadList.adapter
        if (currAdapter == null || contactsAdapter == null) {
            contactsAdapter = ContactsAdapter(
                activity = currentActivity,
                contacts = filtered,
                recyclerView = binding.dialpadList,
                highlightText = text,
                refreshItemsListener = null,
                showNumber = true,
                allowLongClick = false,
                itemClick = {
                    activity?.startCallWithConfirmationCheck(it as Contact)
                    if (config.showCallConfirmation) clearInputWithDelay()
                },
                profileIconClick = {
                    activity?.startContactDetailsIntent(it as Contact)
                }
            )
            binding.dialpadList.adapter = contactsAdapter
        } else {
            // Update existing adapter
            contactsAdapter!!.updateItems(filtered, text)
        }

        if (!initSearch) { //So that there is no adapter update on first launch
            recentsAdapter?.updateItems(filteredRecents)
        }
        initSearch = false

        val inputValue = binding.dialpadInput.value
        val hasInput = inputValue.isNotEmpty()
        val searchEnabled = context.config.searchContactsInDialpad
        val recentsEnabled = context.config.showRecentCallsOnDialpad
        val hasContacts = filtered.isNotEmpty()
        val hasRecents = filteredRecents.isNotEmpty()
        val hasAllRecents = allRecentCalls.isNotEmpty()
        val areMultipleSIMsAvailable = context.areMultipleSIMsAvailable()
        val dialpadStyle = config.dialpadStyle

        binding.dialpadAddNumber.beVisibleIf(hasInput)
        binding.dialpadAddNumber.setTextColor(context.getProperPrimaryColor())
        
        // Show contacts list only when: has input, search enabled, and has matching contacts
        binding.dialpadList.beVisibleIf(hasInput && searchEnabled && hasContacts)
        
        // Show recents list when:
        // 1. No input, recents enabled, and has recents to show
        // 2. Has input, no contacts match, but recents match (fallback to recents)
        // 3. Search disabled, recents enabled, and has recents to show
        val showRecentsList = (!hasInput && recentsEnabled && hasRecents) ||
                (hasInput && !hasContacts && hasRecents && searchEnabled && recentsEnabled) ||
                (!searchEnabled && recentsEnabled && hasRecents)
        binding.dialpadRecentsList.beVisibleIf(showRecentsList)
        
        // Show placeholder when recents are enabled but empty
        // When no input: check allRecentCalls (show if no recents at all)
        // When has input: check filteredRecents (show if no filtered recents match)
        val showPlaceholder = if (hasInput) {
            // Show placeholder if: recents enabled, no filtered recents match, and (no contacts match OR search disabled)
            recentsEnabled && !hasRecents && (!hasContacts || !searchEnabled)
        } else {
            // Show placeholder if: recents enabled and no recents at all
            recentsEnabled && !hasAllRecents
        }
        binding.dialpadRecentsPlaceholder.beVisibleIf(showPlaceholder)
        

        dialpadGridBinding?.dialpadClearCharHolder?.beVisibleIf((hasInput && dialpadStyle != DIALPAD_IOS && dialpadStyle != DIALPAD_CONCEPT) || areMultipleSIMsAvailable)
        binding.dialpadRectWrapper?.dialpadClearCharHolder?.beVisibleIf(dialpadStyle == DIALPAD_CONCEPT)
        binding.dialpadRoundWrapper?.dialpadClearCharIosHolder?.beVisibleIf((hasInput && dialpadStyle == DIALPAD_IOS) || areMultipleSIMsAvailable)
        binding.dialpadInput.beVisibleIf(hasInput)

        refreshMenuItems()
    }

    override fun refreshItems(invalidate: Boolean, needUpdate: Boolean, callback: (() -> Unit)?) {
        if (invalidate) {
            allRecentCalls = emptyList()
            config.recentCallsCache = ""
        }

        if (binding.dialpadInput.value.isNotEmpty()) {
            // If there's input, refresh the filtered results
            // When contact is deleted, refresh contacts list too
            ContactsHelper(context).getContactsWithSecureBoxFilter(
                showOnlyContactsWithNumbers = true,
                loadExtendedFields = false
            ) { contacts ->
                allContacts = contacts.toMutableList()
                dialpadValueChanged(binding.dialpadInput.value)
            }
            // Also refresh the underlying data if needed
            if (needUpdate || config.needUpdateRecents) {
                refreshCallLog(loadAll = true) {
                    callback?.invoke()
                }
            } else {
                callback?.invoke()
            }
        } else if (needUpdate || config.needUpdateRecents) {
            // When needUpdate is true (e.g., call ended or contact deleted), refresh immediately without waiting for animations
            refreshCallLog(loadAll = true) {
                callback?.invoke()
            }
        } else {
            var recents = emptyList<RecentCall>()
            if (!invalidate) {
                try {
                    recents = config.parseRecentCallsCache()
                } catch (_: Exception) {
                    config.recentCallsCache = ""
                }
            }

            if (recents.isNotEmpty()) {
                refreshCallLogFromCache(recents) {
                    binding.dialpadRecentsList.runAfterAnimations {
                        refreshCallLog(loadAll = true) {
                            callback?.invoke()
                        }
                    }
                }
            } else {
                refreshCallLog(loadAll = false) {
                    binding.dialpadRecentsList.runAfterAnimations {
                        refreshCallLog(loadAll = true) {
                            callback?.invoke()
                        }
                    }
                }
            }
        }
    }

    private fun refreshCallLog(loadAll: Boolean = false, callback: (() -> Unit)? = null) {
        getRecentCalls(loadAll) { recents ->
            allRecentCalls = recents
            activity?.runOnUiThread {
                gotRecents(recents)
                callback?.invoke()
            } ?: fragmentScope.launch(Dispatchers.Main) {
                gotRecents(recents)
                callback?.invoke()
            }

            config.recentCallsCache = sharedGson.toJson(recents.take(RECENT_CALL_CACHE_SIZE))

            // Deleting notes if a call has already been deleted
            callerNotesHelper.removeCallerNotes(
                recents.map { it.phoneNumber.numberForNotes() }
            )
        }

        if (loadAll) {
            with(recentsHelper) {
                val queryCount = config.queryLimitRecent
                getRecentCalls(queryLimit = queryCount, updateCallsCache = false) { calls ->
                    ensureBackgroundThread {
                        val recentOutgoingNumbers = calls
                            .mapNotNull { if (it.type == Calls.OUTGOING_TYPE) it.phoneNumber else null }
                            .toMutableSet()

                        config.recentOutgoingNumbers = recentOutgoingNumbers
                    }
                }
            }
        }
    }

    private fun refreshCallLogFromCache(cache: List<RecentCall>, callback: (() -> Unit)? = null) {
        gotRecents(cache)
        callback?.invoke()
    }

    private fun getRecentCalls(loadAll: Boolean, callback: (List<RecentCall>) -> Unit) {
        val queryCount = if (loadAll) config.queryLimitRecent else RecentsHelper.QUERY_LIMIT
        val existingRecentCalls = allRecentCalls

        with(recentsHelper) {
            if (config.groupSubsequentCalls) {
                getGroupedRecentCalls(existingRecentCalls, queryCount, true) {
                    prepareCallLog(it, callback)
                }
            } else {
                getRecentCalls(existingRecentCalls, queryCount, isDialpad = true, updateCallsCache = true) { calls ->
                    val filteredCalls = if (config.groupAllCalls) calls.distinctBy { it.phoneNumber } else calls
                    prepareCallLog(filteredCalls, callback)
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
                val updatedCalls = updateNamesIfEmpty(calls, contacts)
                callback(updatedCalls)
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

    private fun gotRecents(recents: List<RecentCall>) {
        val currAdapter = binding.dialpadRecentsList.adapter
        if (currAdapter == null) {
            val currentActivity = activity ?: return
            recentsAdapter = RecentCallsAdapter(
                activity = currentActivity,
                recyclerView = binding.dialpadRecentsList,
                refreshItemsListener = null,
                showOverflowMenu = true,
                showCallIcon = config.onRecentClick == SWIPE_ACTION_OPEN,
                hideTimeAtOtherDays = true,
                isDialpad = true,
                itemDelete = { deleted ->
                    allRecentCalls = allRecentCalls.filter { it !in deleted }
                },
                itemClick = {
                    itemClickAction(config.onRecentClick, it as RecentCall)
                },
                profileInfoClick = { recentCall ->
                    actionOpen(recentCall)
                },
                profileIconClick = {
                    val recentCall = it as RecentCall
                    val contact = findContactByCall(recentCall)
                    if (contact != null) {
                        activity?.startContactDetailsIntent(contact)
                    } else {
                        activity?.startAddContactIntent(recentCall.phoneNumber)
                    }
                },
                contactsProvider = { ArrayList(allContacts) }
            )

            binding.dialpadRecentsList.adapter = recentsAdapter
            recentsAdapter?.updateItems(recents)
        } else {
            recentsAdapter?.updateItems(recents)
        }
        
        // Update visibility when recents are loaded, especially when input is empty
        val inputValue = binding.dialpadInput.value
        val hasInput = inputValue.isNotEmpty()
        val searchEnabled = config.searchContactsInDialpad
        val recentsEnabled = config.showRecentCallsOnDialpad
        val hasRecents = recents.isNotEmpty()
        
        if (recents.isEmpty()) {
            // No recents: show placeholder, hide list
            binding.dialpadRecentsList.beGone()
            // Show placeholder only when recents are enabled and (no input or search disabled)
            val showPlaceholder = recentsEnabled && (!hasInput || !searchEnabled)
            binding.dialpadRecentsPlaceholder.beVisibleIf(showPlaceholder)
        } else {
            // Has recents: hide placeholder, show list conditionally
            binding.dialpadRecentsPlaceholder.beGone()
            // Show recents list when:
            // 1. No input, recents enabled, and has recents to show
            // 2. Search disabled, recents enabled, and has recents to show
            // Note: When there's input, visibility is handled by dialpadValueChanged()
            val showRecentsList = (!hasInput && recentsEnabled && hasRecents) ||
                    (!searchEnabled && recentsEnabled && hasRecents)
            binding.dialpadRecentsList.beVisibleIf(showRecentsList)
        }
    }

    private fun gotContacts(newContacts: ArrayList<Contact>) {
        allContacts = newContacts

        activity?.runOnUiThread {
            if (!checkDialIntent() && binding.dialpadInput.value.isEmpty()) {
                dialpadValueChanged("")
            }
        } ?: fragmentScope.launch(Dispatchers.Main) {
            if (!checkDialIntent() && binding.dialpadInput.value.isEmpty()) {
                dialpadValueChanged("")
            }
        }
    }

    private fun checkDialIntent(): Boolean {
        val activity = activity ?: return false
        val intent = activity.intent ?: return false

        return if (
            (intent.action == Intent.ACTION_DIAL || intent.action == Intent.ACTION_VIEW)
            && intent.data != null && intent.dataString?.contains("tel:") == true
        ) {
            val number = Uri.decode(intent.dataString).substringAfter("tel:")
            binding.dialpadInput.setText(number)
            binding.dialpadInput.setSelection(number.length)
            true
        } else {
            false
        }
    }

    private fun findContactByCall(recentCall: RecentCall): Contact? {
        return allContacts.firstOrNull { contact ->
            contact.doesHavePhoneNumber(recentCall.phoneNumber)
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
        if (config.showCallConfirmation) {
            val currentActivity = activity
            val blurTarget = currentActivity?.findViewById<BlurTarget>(R.id.mainBlurTarget)
            if (blurTarget != null) {
                CallConfirmationDialog(currentActivity!!, call.name, blurTarget = blurTarget) {
                    callRecentNumber(call)
                }
            } else {
                callRecentNumber(call)
            }
        } else {
            callRecentNumber(call)
        }
    }

    private fun actionSMS(call: RecentCall) {
        activity?.launchSendSMSIntentRecommendation(call.phoneNumber)
    }

    private fun callRecentNumber(recentCall: RecentCall) {
        if (config.callUsingSameSim && recentCall.simID > 0) {
            val sim = recentCall.simID == 1
            activity?.callContactWithSim(recentCall.phoneNumber, sim)
        } else {
            activity?.launchCallIntent(recentCall.phoneNumber, key = BuildConfig.RIGHT_APP_KEY)
        }
    }

    private fun actionOpen(call: RecentCall) {
        val currentActivity = activity ?: return
        val recentCalls = call.groupedCalls as ArrayList<RecentCall>? ?: arrayListOf(call)
        val contact = findContactByCall(call)
        Intent(currentActivity.applicationContext, CallHistoryActivity::class.java).apply {
            putExtra(CURRENT_RECENT_CALL, call)
            putExtra(CURRENT_RECENT_CALL_LIST, recentCalls)
            putExtra(CONTACT_ID, call.contactID)
            if (contact != null) {
                putExtra(IS_PRIVATE, contact.isPrivate())
            }
            currentActivity.launchActivityIntent(this)
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, accentColor: Int) {
        // Update cached theme values
        updateCachedThemeValues()
        
        // Check for style/color changes (similar to onResume in DialpadActivity)
        val dialpadStyle = config.dialpadStyle
        val styleChanged = storedDialpadStyle != dialpadStyle
        val currentBackgroundColor = cachedProperBackgroundColor ?: context.getProperBackgroundColor()
        val backgroundColorChanged = storedBackgroundColor != currentBackgroundColor

        // Check for other dialpad settings changes
        val dialpadSizeChanged = storedDialpadSize != config.dialpadSize
        val callButtonPrimarySizeChanged = storedCallButtonPrimarySize != config.callButtonPrimarySize
        val callButtonSecondarySizeChanged = storedCallButtonSecondarySize != config.callButtonSecondarySize
        val dialpadBottomMarginChanged = storedDialpadBottomMargin != config.dialpadBottomMargin
        val hideDialpadNumbersChanged = storedHideDialpadNumbers != config.hideDialpadNumbers
        val hideDialpadLettersChanged = storedHideDialpadLetters != config.hideDialpadLetters
        val dialpadSecondaryLanguageChanged = storedDialpadSecondaryLanguage != config.dialpadSecondaryLanguage
        val dialpadSecondaryTypefaceChanged = storedDialpadSecondaryTypeface != config.dialpadSecondaryTypeface
        val showVoicemailIconChanged = storedShowVoicemailIcon != config.showVoicemailIcon
        val formatPhoneNumbersChanged = storedFormatPhoneNumbers != config.formatPhoneNumbers

        if (styleChanged || backgroundColorChanged) {
            // Style or background color changed, reinitialize
            storedDialpadStyle = dialpadStyle
            storedBackgroundColor = currentBackgroundColor
            
            // Invalidate cached dialpad view
            cachedDialpadView = null
            cachedDialpadStyle = -1

            // Reinitialize everything for the new style
            initStyle()
        }

        // Always update dialpad size (like DialpadActivity.onResume())
        updateDialpadSize()
        storedDialpadSize = config.dialpadSize
        storedDialpadBottomMargin = config.dialpadBottomMargin

        // Update call button size if needed
        if (dialpadStyle == DIALPAD_GRID || dialpadStyle == DIALPAD_ORIGINAL) {
            updateCallButtonSize()
            storedCallButtonPrimarySize = config.callButtonPrimarySize
            storedCallButtonSecondarySize = config.callButtonSecondarySize
        }

        // Handle hideDialpadNumbers change
        if (hideDialpadNumbersChanged) {
            storedHideDialpadNumbers = config.hideDialpadNumbers
            handleHideDialpadNumbers()
        }

        // Handle hideDialpadLetters change - requires reinitializing letters
        if (hideDialpadLettersChanged || dialpadSecondaryLanguageChanged || dialpadSecondaryTypefaceChanged || styleChanged) {
            storedHideDialpadLetters = config.hideDialpadLetters
            storedDialpadSecondaryLanguage = config.dialpadSecondaryLanguage
            storedDialpadSecondaryTypeface = config.dialpadSecondaryTypeface
            // Reinitialize style to update letters
            if (!styleChanged) {
                initStyle()
            }
        }

        // Handle showVoicemailIcon change
        if (showVoicemailIconChanged || styleChanged) {
            storedShowVoicemailIcon = config.showVoicemailIcon
            // Voicemail icon visibility is handled in initStyle()
            if (!styleChanged) {
                initStyle()
            }
        }

        // Handle formatPhoneNumbers change
        if (formatPhoneNumbersChanged) {
            storedFormatPhoneNumbers = config.formatPhoneNumbers
            binding.dialpadInput.apply {
                // Remove existing CustomPhoneNumberFormattingTextWatcher if present
                phoneNumberFormattingWatcher?.let { removeTextChangedListener(it) }
                phoneNumberFormattingWatcher = null

                // Add CustomPhoneNumberFormattingTextWatcher if enabled
                if (config.formatPhoneNumbers) {
                    phoneNumberFormattingWatcher = CustomPhoneNumberFormattingTextWatcher()
                    addTextChangedListener(phoneNumberFormattingWatcher)
                }
            }
        }

        // Ensure dialpad is visible after style change
        if (styleChanged) {
            val dialpadViewAfterStyle = dialpadView()
            dialpadViewAfterStyle?.apply {
                visibility = View.VISIBLE
                translationY = 0f
                // Post to ensure layout is complete, especially for iOS style
                post {
                    visibility = View.VISIBLE
                    translationY = 0f
                    // Force a layout pass for iOS dialpad
                    if (dialpadStyle == DIALPAD_IOS) {
                        requestLayout()
                        invalidate()
                    }
                }
            }
        }

        // Update TalkBack state
        isTalkBackOn = context.isTalkBackOn()

        if (storedToneVolume != config.toneVolume) {
            // Tone volume changed, recreate tone generator
            storedToneVolume = config.toneVolume
            toneGeneratorHelper?.stopTone()
            toneGeneratorHelper = ToneGeneratorHelper(context, DIALPAD_TONE_LENGTH_MS)
        }

        // Use cached colors
        val properBackgroundColor = getCachedBackgroundColor()
        val properTextColor = context.getProperTextColor()
        val properPrimaryColor = context.getProperPrimaryColor()

        // Update background colors
        setBackgroundColor(properBackgroundColor)
        binding.dialpadList.setBackgroundColor(properBackgroundColor)
        binding.dialpadRecentsList.setBackgroundColor(properBackgroundColor)
        
        // Update placeholder text color
        binding.dialpadRecentsPlaceholder.setTextColor(properTextColor)

        // Update appbar style - use cached appbar background color
        val appbarBackgroundColor = getCachedAppbarBackgroundColor()
        activity?.let { act ->
            act.setupTopAppBar(
                topAppBar = binding.dialpadAppbar,
                navigationIcon = NavigationIcon.None,
                topBarColor = appbarBackgroundColor,
                navigationClick = false
            )
        }
        // Explicitly set toolbar background color to match appbar
        binding.dialpadToolbar.setBackgroundColor(appbarBackgroundColor)

        // Update input colors
        binding.dialpadInput.setTextColor(properTextColor)
        binding.dialpadInput.setHintTextColor(properTextColor.adjustAlpha(0.6f))
        binding.dialpadAddNumber.setTextColor(properPrimaryColor)

        // Update dialpadRoundWrapperUp colors
        val simOneColor = context.config.simIconsColors[1]
        binding.dialpadRoundWrapperUp.background?.mutate()?.setColorFilter(simOneColor, android.graphics.PorterDuff.Mode.SRC_IN)
        binding.dialpadRoundWrapperUp.setColorFilter(simOneColor.getContrastColor())

        dialpadGridBinding?.apply {
            // Update clear button colors
            dialpadClearChar?.applyColorFilter(Color.GRAY)
            dialpadClearChar?.setAlpha((0.4f * 255).toInt())
            dialpadClearCharX?.applyColorFilter(properTextColor)

            // Update call button colors
            val simOnePrimary = context.config.currentSIMCardIndex == 0
            val simOneColor = if (simOnePrimary) context.config.simIconsColors[1] else context.config.simIconsColors[2]
            val drawablePrimary = if (simOnePrimary) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
            val callIconId = if (context.areMultipleSIMsAvailable()) drawablePrimary else R.drawable.ic_phone_vector
            val callIcon = context.resources.getColoredDrawableWithColor(context, callIconId, simOneColor.getContrastColor())
            dialpadCallButton.apply {
                setImageDrawable(callIcon)
                background?.mutate()?.setColorFilter(simOneColor, android.graphics.PorterDuff.Mode.SRC_IN)
            }

            // Update secondary call button if available
            if (context.areMultipleSIMsAvailable()) {
                val simTwoColor = if (simOnePrimary) context.config.simIconsColors[2] else context.config.simIconsColors[1]
                val drawableSecondary = if (simOnePrimary) R.drawable.ic_phone_two_vector else R.drawable.ic_phone_one_vector
                val callIconSecondary = context.resources.getColoredDrawableWithColor(context, drawableSecondary, simTwoColor.getContrastColor())
                dialpadCallTwoButton?.apply {
                    setImageDrawable(callIconSecondary)
                    background?.mutate()?.setColorFilter(simTwoColor, android.graphics.PorterDuff.Mode.SRC_IN)
                }
            }
        }

        // Update speed dial values
        speedDialValues = config.getSpeedDialValues()
    }
    
    // Helper methods for caching theme and color values
    private fun updateCachedThemeValues() {
        cachedIsDynamicTheme = context.isDynamicTheme()
        cachedIsSystemInDarkMode = context.isSystemInDarkMode()
        cachedProperBackgroundColor = context.getProperBackgroundColor()
        cachedSurfaceColor = context.getSurfaceColor()
        // Appbar should match main background color - use surface color only in light mode with dynamic theme
        val useSurfaceColor = cachedIsDynamicTheme == true && cachedIsSystemInDarkMode == false
        cachedAppbarBackgroundColor = if (useSurfaceColor) cachedSurfaceColor else cachedProperBackgroundColor
    }
    
    private fun getCachedBackgroundColor(): Int {
        if (cachedProperBackgroundColor == null || cachedSurfaceColor == null) {
            updateCachedThemeValues()
        }
        val useSurfaceColor = cachedIsDynamicTheme == true && cachedIsSystemInDarkMode == false
        return if (useSurfaceColor) (cachedSurfaceColor ?: context.getSurfaceColor()) 
               else (cachedProperBackgroundColor ?: context.getProperBackgroundColor())
    }
    
    private fun getCachedAppbarBackgroundColor(): Int {
        // Appbar should always match the main background color
        return getCachedBackgroundColor()
    }

    override fun onSearchClosed() {
        // Dialpad doesn't use search functionality
    }

    override fun onSearchQueryChanged(text: String) {
        // Dialpad doesn't use search functionality
    }

    override fun myRecyclerView(): MyRecyclerView {
        // Return the contacts list RecyclerView
        return binding.dialpadList
    }

    fun onFragmentResume() {
        isResumed = true
        // Register ContentObserver to detect contact changes
        registerContactObserver()
        // Refresh contacts on resume to catch changes made while app was in background
        refreshContacts()
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
                                refreshContacts()
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
    
    private fun refreshContacts() {
        ContactsHelper(context).getContactsWithSecureBoxFilter(
            showOnlyContactsWithNumbers = true,
            loadExtendedFields = false
        ) { contacts ->
            allContacts = contacts.toMutableList()
            // If there's input, refresh the search results with new contacts
            val currentInput = binding.dialpadInput.value
            if (currentInput.isNotEmpty()) {
                activity?.runOnUiThread {
                    dialpadValueChanged(currentInput)
                    // Also refresh recents to update contact names when contacts change
                    if (config.showRecentCallsOnDialpad) {
                        refreshCallLog(loadAll = true)
                    }
                } ?: fragmentScope.launch(Dispatchers.Main) {
                    dialpadValueChanged(currentInput)
                    // Also refresh recents to update contact names when contacts change
                    if (config.showRecentCallsOnDialpad) {
                        refreshCallLog(loadAll = true)
                    }
                }
            } else {
                gotContacts(contacts)
                // Also refresh recents to update contact names when contacts change
                if (config.showRecentCallsOnDialpad) {
                    refreshItems(needUpdate = true)
                }
            }
        }
    }

    fun getDialedNumber(): String {
        return binding.dialpadInput.value.removeNumberFormatting()
    }

    fun clearInput() {
        binding.dialpadInput.setText("")
    }

    // Expose methods for MainActivity menu handling
    fun copyNumber() {
        val clip = binding.dialpadInput.value
        activity?.copyToClipboard(clip)
    }

    fun webSearch() {
        val text = binding.dialpadInput.value
        activity?.launchInternetSearch(text)
    }

    @SuppressLint("StringFormatInvalid")
    fun initCallAnonymous() {
        val dialpadValue = binding.dialpadInput.value
        if (dialpadValue.isEmpty()) return
        
        val numberToCall = "#31#$dialpadValue"
        if (context.config.showWarningAnonymousCall) {
            val text = String.format(context.getString(R.string.call_anonymously_warning), dialpadValue)
            val currentActivity = activity
            val blurTarget = currentActivity?.findViewById<BlurTarget>(R.id.mainBlurTarget)
            if (blurTarget != null) {
                ConfirmationAdvancedDialog(
                    currentActivity!!,
                    text,
                    R.string.call_anonymously_warning,
                    R.string.ok,
                    R.string.do_not_show_again,
                    blurTarget = blurTarget,
                    fromHtml = true
                ) {
                    if (it) {
                        context.config.showWarningAnonymousCall = false
                    }
                    initCall(numberToCall, 0)
                }
            } else {
                initCall(numberToCall, 0)
            }
        } else {
            initCall(numberToCall, 0)
        }
    }

    fun showBlockedNumbers() {
        context.config.showBlockedNumbers = !context.config.showBlockedNumbers
        context.config.needUpdateRecents = true
        activity?.runOnUiThread {
            refreshItems()
        } ?: fragmentScope.launch(Dispatchers.Main) {
            refreshItems()
        }
        refreshMenuItems()
    }

    fun clearCallHistory() {
        val currentActivity = activity ?: return
        val confirmationText = "${context.getString(R.string.clear_history_confirmation)}\n\n${context.getString(R.string.cannot_be_undone)}"
        val blurTarget = currentActivity.findViewById<BlurTarget>(R.id.mainBlurTarget)
        if (blurTarget != null) {
            ConfirmationDialog(currentActivity, confirmationText, blurTarget = blurTarget) {
                RecentsHelper(context).removeAllRecentCalls(currentActivity) {
                    allRecentCalls = emptyList()
                    activity?.runOnUiThread {
                        refreshItems(invalidate = true)
                    } ?: fragmentScope.launch(Dispatchers.Main) {
                        refreshItems(invalidate = true)
                    }
                }
            }
        } else {
            // Fallback: show a simple toast as warning
            context.toast(R.string.clear_history_confirmation)
        }
    }

    private fun clearInputWithDelay() {
        fragmentScope.launch {
            delay(1000)
            clearInput()
        }
    }

    private fun initCall(number: String = binding.dialpadInput.value, handleIndex: Int, displayName: String? = null) {
        val numberToCall = if (number.isNotEmpty()) number else getDialedNumber()
        if (numberToCall.isNotEmpty()) {
            val nameToDisplay = displayName ?: numberToCall
            if (handleIndex != -1 && context.areMultipleSIMsAvailable()) {
                activity?.callContactWithSimWithConfirmationCheck(numberToCall, nameToDisplay, handleIndex == 0)
            } else {
                activity?.startCallWithConfirmationCheck(numberToCall, nameToDisplay)
            }
            if (context.config.dialpadClearWhenStartCall) {
                clearInputWithDelay()
            }
        } else {
            RecentsHelper(context).getRecentCalls(queryLimit = 1) {
                val mostRecentNumber = it.firstOrNull()?.phoneNumber
                if (!mostRecentNumber.isNullOrEmpty()) {
                    activity?.runOnUiThread {
                        binding.dialpadInput.setText(mostRecentNumber)
                        binding.dialpadInput.setSelection(mostRecentNumber.length)
                    } ?: fragmentScope.launch(Dispatchers.Main) {
                        binding.dialpadInput.setText(mostRecentNumber)
                        binding.dialpadInput.setSelection(mostRecentNumber.length)
                    }
                }
            }
        }
    }

    private fun initCallWithSim(simOne: Boolean) {
        val number = getDialedNumber()
        if (number.isNotEmpty()) {
            activity?.callContactWithSimWithConfirmationCheck(number, number, simOne)
            if (context.config.dialpadClearWhenStartCall) {
                clearInputWithDelay()
            }
        }
    }

    private fun dialpadView(): View? {
        val currentStyle = context.config.dialpadStyle
        if (cachedDialpadView == null || cachedDialpadStyle != currentStyle) {
            cachedDialpadStyle = currentStyle
            cachedDialpadView = when (currentStyle) {
                DIALPAD_IOS -> binding.dialpadRoundWrapper.root
                DIALPAD_CONCEPT -> binding.dialpadRectWrapper.root
                else -> binding.dialpadClearWrapper.root
            }
        }
        return cachedDialpadView
    }

    private fun dialpadHide() {
        val view = dialpadView()
        if (view?.isVisible == true) {
            slideDown(view)
        } else {
            slideUp(view)
        }
    }

    private fun slideDown(view: View?) {
        view ?: return
        view.animate()
            .translationY(view.height.toFloat())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                    view.translationY = 0f
                }
            })
        hasBeenScrolled = false
        // Show dialpadRoundWrapperUp when dialpad is hidden
        if ((view == binding.dialpadRoundWrapper?.root ||
                view == dialpadGridBinding?.root ||
                view == binding.dialpadRectWrapper?.root) &&
            binding.dialpadRoundWrapperUp.isGone
        ) {
            // Ensure margins are set before showing the button
            ensureDialpadButtonMargins()
            // Post to ensure layout is complete before showing
            binding.dialpadRoundWrapperUp.post {
                slideUp(binding.dialpadRoundWrapperUp)
            }
        }
    }

    private fun slideUp(view: View?) {
        view ?: return
        view.visibility = View.VISIBLE
        //view.alpha = 0f
        if (view.height > 0) {
            slideUpNow(view)
        } else {
            // wait till height is measured
            view.post {
                if (view.height > 0) {
                    slideUpNow(view)
                } else {
                    // If still not measured, use ViewTreeObserver
                    view.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            slideUpNow(view)
                        }
                    })
                }
            }
        }
        // Hide dialpadRoundWrapperUp when dialpad is shown
        if (view == binding.dialpadRoundWrapper?.root ||
            view == dialpadGridBinding?.root ||
            view == binding.dialpadRectWrapper?.root
        ) {
            slideDown(binding.dialpadRoundWrapperUp)
        }
    }

    private fun slideUpNow(view: View) {
        view.translationY = view.height.toFloat()
        view.animate()
            .translationY(0f)
            //.alpha(1f)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.VISIBLE
                    //view.alpha = 1f
                }
            })
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshCallLog(event: Events.RefreshCallLog) {
        context.config.needUpdateRecents = true
        // Refresh immediately when call ends, don't wait for animations
        refreshItems(needUpdate = true)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshDialpadSettings(event: Events.RefreshDialpadSettings) {
        // Refresh dialpad settings immediately when settings are changed
        val properTextColor = context.getProperTextColor()
        val properPrimaryColor = context.getProperPrimaryColor()
        setupColors(properTextColor, properPrimaryColor, context.getProperAccentColor())
    }

    private fun setupOptionsMenu() {
        binding.dialpadToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.paste_number -> {
                    val text = activity?.getTextFromClipboard()
                    binding.dialpadInput.setText(text)
                    if (!text.isNullOrEmpty()) {
                        binding.dialpadInput.setSelection(text.length)
                        binding.dialpadInput.requestFocusFromTouch()
                    }
                }

                R.id.copy_number -> copyNumber()
                R.id.web_search -> webSearch()
//                R.id.cab_call_anonymously -> initCallAnonymous()
                R.id.show_blocked_numbers -> showBlockedNumbers()
                R.id.clear_call_history -> clearCallHistory()
                R.id.settings_dialpad -> activity?.let { act ->
                    act.startActivity(Intent(act.applicationContext, com.android.dialer.activities.SettingsDialpadActivity::class.java))
                }

                R.id.settings -> activity?.let { act ->
                    act.startActivity(Intent(act.applicationContext, com.android.dialer.activities.SettingsActivity::class.java))
                }
                R.id.add_number_to_contact -> addNumberToContact()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun handleHideDialpadNumbers() {
        dialpadGridBinding?.apply {
            dialpad1Holder.beGone()
            dialpad2Holder.beGone()
            dialpad3Holder.beGone()
            dialpad4Holder.beGone()
            dialpad5Holder?.beGone()
            dialpad6Holder?.beGone()
            dialpad7Holder?.beGone()
            dialpad8Holder?.beGone()
            dialpad9Holder?.beGone()
            dialpad0Holder?.visibility = View.INVISIBLE
        }
    }

    private fun initStyle() {
        if (!DialpadT9.Initialized) {
            val reader = InputStreamReader(context.resources.openRawResource(R.raw.t9languages))
            DialpadT9.readFromJson(reader.readText())
        }

        val dialpadRoundWrapper = binding.dialpadRoundWrapper
        val dialpadRectWrapper = binding.dialpadRectWrapper
        val dialpadGridWrapper = dialpadGridBinding?.root
        val properBackgroundColor = getCachedBackgroundColor()

        when (config.dialpadStyle) {
            DIALPAD_IOS -> {

                dialpadGridWrapper?.beGone()
                dialpadRectWrapper.root.beGone()
                dialpadRoundWrapper.apply {
                    dialpadVoicemail.beVisibleIf(config.showVoicemailIcon)
                    dialpadIosHolder.beVisible()
                    dialpadIosHolder.setBackgroundColor(properBackgroundColor)

                    arrayOf(
                        dialpad0IosHolder, dialpad1IosHolder, dialpad2IosHolder, dialpad3IosHolder, dialpad4IosHolder,
                        dialpad5IosHolder, dialpad6IosHolder, dialpad7IosHolder, dialpad8IosHolder, dialpad9IosHolder,
                        dialpadAsteriskIosHolder, dialpadHashtagIosHolder
                    ).forEach {
                        it.foreground?.applyColorFilter(Color.GRAY)
                        it.foreground?.alpha = 60
                    }

                    val properTextColor = context.getProperTextColor()
                    arrayOf(dialpadAsteriskIos, dialpadHashtagIos, dialpadVoicemail).forEach {
                        it.applyColorFilter(properTextColor)
                    }

                    dialpadBottomMargin.apply {
                        setBackgroundColor(properBackgroundColor)
                        setHeight(100)
                    }
                }
                initLettersIos()
            }

            DIALPAD_CONCEPT -> {
                dialpadRoundWrapper.root.beGone()
                dialpadGridWrapper?.beGone()
                dialpadRectWrapper.root.beVisible()
                dialpadRectWrapper.apply {
                    dialpadVoicemail.beVisibleIf(config.showVoicemailIcon)
                    dialpadGridWrapper?.setBackgroundColor(properBackgroundColor)

                    dialpadBottomMargin.apply {
                        setBackgroundColor(properBackgroundColor)
                        setHeight(100)
                    }
                }
                initLettersConcept()
            }

            DIALPAD_GRID -> {
                dialpadRoundWrapper.root.beGone()
                dialpadRectWrapper.root.beGone()
                dialpadGridWrapper?.beVisible()
                initLetters()
            }

            else -> {
                dialpadRoundWrapper.root.beGone()
                dialpadRectWrapper.root.beGone()
                dialpadGridWrapper?.beVisible()
                initLetters()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initLetters() {
        val areMultipleSIMsAvailable = context.areMultipleSIMsAvailable()
        val properTextColor = context.getProperTextColor()

        dialpadGridBinding?.apply {
            if (config.hideDialpadLetters) {
                arrayOf(
                    dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                    dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach { it?.beGone() }
            } else {
                dialpad1Letters?.apply {
                    beInvisible()
                    setTypeface(null, context.config.dialpadSecondaryTypeface)
                }
                arrayOf(
                    dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                    dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it?.beVisible()
                    it?.setTypeface(null, context.config.dialpadSecondaryTypeface)
                }

                val langPref = context.config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref != null && langPref != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
                    val lang = if (isAutoLang) langLocale else langPref
                    dialpad1Letters?.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad2Letters?.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad3Letters?.text = DialpadT9.getLettersForNumber(3, lang) + "\nDEF"
                    dialpad4Letters?.text = DialpadT9.getLettersForNumber(4, lang) + "\nGHI"
                    dialpad5Letters?.text = DialpadT9.getLettersForNumber(5, lang) + "\nJKL"
                    dialpad6Letters?.text = DialpadT9.getLettersForNumber(6, lang) + "\nMNO"
                    dialpad7Letters?.text = DialpadT9.getLettersForNumber(7, lang) + "\nPQRS"
                    dialpad8Letters?.text = DialpadT9.getLettersForNumber(8, lang) + "\nTUV"
                    dialpad9Letters?.text = DialpadT9.getLettersForNumber(9, lang) + "\nWXYZ"

                    val fontSizeRu = context.getTextSize() - 16f
                    arrayOf(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach { it?.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeRu) }
                } else {
                    val fontSize = context.getTextSize() - 8f
                    arrayOf(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach { it?.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize) }
                }
            }

            // Setup number buttons (1-9, 0, *, #) - this needs to be done every time initLetters is called
            setupCharClick(dialpad1Holder, '1')
            setupCharClick(dialpad2Holder, '2')
            setupCharClick(dialpad3Holder, '3')
            setupCharClick(dialpad4Holder, '4')
            setupCharClick(dialpad5Holder, '5')
            setupCharClick(dialpad6Holder, '6')
            setupCharClick(dialpad7Holder, '7')
            setupCharClick(dialpad8Holder, '8')
            setupCharClick(dialpad9Holder, '9')
            setupCharClick(dialpad0Holder, '0')
            setupCharClick(dialpadAsteriskHolder, '*')
            setupCharClick(dialpadHashtagHolder, '#')

            val simOnePrimary = context.config.currentSIMCardIndex == 0
            dialpadCallTwoButton?.apply {
                if (areMultipleSIMsAvailable) {
                    beVisible()
                    val simTwoColor = if (simOnePrimary) context.config.simIconsColors[2] else context.config.simIconsColors[1]
                    val drawableSecondary = if (simOnePrimary) R.drawable.ic_phone_two_vector else R.drawable.ic_phone_one_vector
                    val callIcon = context.resources.getColoredDrawableWithColor(context, drawableSecondary, simTwoColor.getContrastColor())
                    setImageDrawable(callIcon)
                    background?.mutate()?.setColorFilter(simTwoColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    setOnClickListener {
                        initCall(binding.dialpadInput.value, if (simOnePrimary) 1 else 0)
                        maybePerformDialpadHapticFeedback(this)
                    }
                    contentDescription = context.getString(if (simOnePrimary) R.string.call_from_sim_2 else R.string.call_from_sim_1)
                } else {
                    beGone()
                }
            }

            val simOneColor = if (simOnePrimary) context.config.simIconsColors[1] else context.config.simIconsColors[2]
            val drawablePrimary = if (simOnePrimary) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
            val callIconId = if (areMultipleSIMsAvailable) drawablePrimary else R.drawable.ic_phone_vector
            val callIcon = context.resources.getColoredDrawableWithColor(context, callIconId, simOneColor.getContrastColor())
            dialpadCallButton.apply {
                setImageDrawable(callIcon)
                background?.mutate()?.setColorFilter(simOneColor, android.graphics.PorterDuff.Mode.SRC_IN)
                setOnClickListener {
                    initCall(handleIndex = if (simOnePrimary || !areMultipleSIMsAvailable) 0 else 1)
                    maybePerformDialpadHapticFeedback(this)
                }
                setOnLongClickListener {
                    if (binding.dialpadInput.value.isEmpty()) {
                        val text = activity?.getTextFromClipboard()
                        binding.dialpadInput.setText(text)
                        if (!text.isNullOrEmpty()) {
                            binding.dialpadInput.setSelection(text.length)
                            binding.dialpadInput.requestFocusFromTouch()
                        }
                        true
                    } else {
                        copyNumber()
                        true
                    }
                }
                contentDescription = context.getString(
                    if (areMultipleSIMsAvailable) {
                        if (simOnePrimary) R.string.call_from_sim_1 else R.string.call_from_sim_2
                    } else R.string.call
                )
            }

            dialpadClearCharHolder?.beVisibleIf((binding.dialpadInput.value.isNotEmpty()) || areMultipleSIMsAvailable)
            dialpadClearChar?.applyColorFilter(Color.GRAY)
            dialpadClearChar?.setAlpha((0.4f * 255).toInt())
            dialpadClearCharX?.applyColorFilter(properTextColor)
            dialpadClearCharHolder?.setOnClickListener { clearChar(it) }
            dialpadClearCharHolder?.setOnLongClickListener { clearInput(); true }

            dialpadGridHolder?.setOnClickListener { } // Do not press between the buttons
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initLettersIos() {
        val areMultipleSIMsAvailable = context.areMultipleSIMsAvailable()
        val properTextColor = context.getProperTextColor()
        binding.dialpadRoundWrapper?.apply {
            if (context.config.hideDialpadLetters) {
                arrayOf(
                    dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                    dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters,
                    dialpad1IosLetters
                ).forEach {
                    it.beGone()
                }
            } else {
                dialpad1IosLetters.apply {
                    beInvisible()
                    setTypeface(null, context.config.dialpadSecondaryTypeface)
                }
                arrayOf(
                    dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                    dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters
                ).forEach {
                    it.beVisible()
                    it.setTypeface(null, context.config.dialpadSecondaryTypeface)
                }

                val langPref = context.config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref != null && langPref != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
                    val lang = if (isAutoLang) langLocale else langPref
                    dialpad1IosLetters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad2IosLetters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad3IosLetters.text = DialpadT9.getLettersForNumber(3, lang) + "\nDEF"
                    dialpad4IosLetters.text = DialpadT9.getLettersForNumber(4, lang) + "\nGHI"
                    dialpad5IosLetters.text = DialpadT9.getLettersForNumber(5, lang) + "\nJKL"
                    dialpad6IosLetters.text = DialpadT9.getLettersForNumber(6, lang) + "\nMNO"
                    dialpad7IosLetters.text = DialpadT9.getLettersForNumber(7, lang) + "\nPQRS"
                    dialpad8IosLetters.text = DialpadT9.getLettersForNumber(8, lang) + "\nTUV"
                    dialpad9IosLetters.text = DialpadT9.getLettersForNumber(9, lang) + "\nWXYZ"

                    val fontSizeRu = context.getTextSize() - 16f
                    arrayOf(
                        dialpad1IosLetters, dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                        dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeRu)
                    }
                } else {
                    val fontSize = context.getTextSize() - 8f
                    arrayOf(
                        dialpad1IosLetters, dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                        dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                    }
                }
            }

            // Setup number buttons
            setupCharClick(dialpad1IosHolder, '1')
            setupCharClick(dialpad2IosHolder, '2')
            setupCharClick(dialpad3IosHolder, '3')
            setupCharClick(dialpad4IosHolder, '4')
            setupCharClick(dialpad5IosHolder, '5')
            setupCharClick(dialpad6IosHolder, '6')
            setupCharClick(dialpad7IosHolder, '7')
            setupCharClick(dialpad8IosHolder, '8')
            setupCharClick(dialpad9IosHolder, '9')
            setupCharClick(dialpad0IosHolder, '0')
            setupCharClick(dialpadAsteriskIosHolder, '*')
            setupCharClick(dialpadHashtagIosHolder, '#')
            dialpadIosHolder.setOnClickListener { } // Do not press between the buttons

            if (areMultipleSIMsAvailable) {
                dialpadSimIosHolder.beVisible()
                dialpadSimIos.background.applyColorFilter(Color.GRAY)
                dialpadSimIos.background.alpha = 60
                dialpadSimIos.applyColorFilter(properTextColor)
                dialpadSimIosHolder.setOnClickListener {
                    if (context.config.currentSIMCardIndex == 0) {
                        context.config.currentSIMCardIndex = 1
                    } else {
                        context.config.currentSIMCardIndex = 0
                    }
                    updateCallButtonIos()
                    maybePerformDialpadHapticFeedback(dialpadSimIosHolder)
                    RxAnimation.from(dialpadCallButtonIosHolder)
                        .shake()
                        .subscribe()
                }
                updateCallButtonIos()
                dialpadCallButtonIosHolder.setOnClickListener {
                    initCall(binding.dialpadInput.value, context.config.currentSIMCardIndex)
                    maybePerformDialpadHapticFeedback(dialpadCallButtonIosHolder)
                }
                dialpadCallButtonIosHolder.contentDescription = context.getString(
                    if (context.config.currentSIMCardIndex == 0) R.string.call_from_sim_1 else R.string.call_from_sim_2
                )
            } else {
                dialpadSimIosHolder.beGone()
                val color = context.config.simIconsColors[1]
                val callIcon = context.resources.getColoredDrawableWithColor(context, R.drawable.ic_phone_vector, color.getContrastColor())
                dialpadCallButtonIosIcon.setImageDrawable(callIcon)
                dialpadCallButtonIosHolder.background.applyColorFilter(color)
                dialpadCallButtonIosHolder.setOnClickListener {
                    initCall(binding.dialpadInput.value, 0)
                    maybePerformDialpadHapticFeedback(dialpadCallButtonIosHolder)
                }
                dialpadCallButtonIosHolder.contentDescription = context.getString(R.string.call)
            }

            dialpadCallButtonIosHolder.setOnLongClickListener {
                if (binding.dialpadInput.value.isEmpty()) {
                    val text = activity?.getTextFromClipboard()
                    binding.dialpadInput.setText(text)
                    if (!text.isNullOrEmpty()) {
                        binding.dialpadInput.setSelection(text.length)
                        binding.dialpadInput.requestFocusFromTouch()
                    }
                    true
                } else {
                    copyNumber()
                    true
                }
            }

            dialpadClearCharIos.applyColorFilter(Color.GRAY)
            dialpadClearCharIos.alpha = 0.235f
            dialpadClearCharXIos.applyColorFilter(properTextColor)
            dialpadClearCharIosHolder.beVisibleIf(binding.dialpadInput.value.isNotEmpty() || areMultipleSIMsAvailable)
            dialpadClearCharIosHolder.setOnClickListener { clearChar(it) }
            dialpadClearCharIosHolder.setOnLongClickListener { clearInput(); true }
        }
        binding.dialpadAddNumber.setOnClickListener { addNumberToContact() }

        // Update dialpadRoundWrapperUp colors
        val simOneColor = context.config.simIconsColors[1]
        binding.dialpadRoundWrapperUp.background?.mutate()?.setColorFilter(simOneColor, android.graphics.PorterDuff.Mode.SRC_IN)
        binding.dialpadRoundWrapperUp.setColorFilter(simOneColor.getContrastColor())
    }

    private fun updateCallButtonIos() {
        val oneSim = context.config.currentSIMCardIndex == 0
        val simColor = if (oneSim) context.config.simIconsColors[1] else context.config.simIconsColors[2]
        val callIconId = if (oneSim) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
        val callIcon = context.resources.getColoredDrawableWithColor(context, callIconId, simColor.getContrastColor())
        binding.dialpadRoundWrapper.dialpadCallButtonIosIcon.setImageDrawable(callIcon)
        binding.dialpadRoundWrapper.dialpadCallButtonIosHolder.background?.applyColorFilter(simColor)
    }

    @SuppressLint("SetTextI18n")
    private fun initLettersConcept() {
        val areMultipleSIMsAvailable = context.areMultipleSIMsAvailable()
        val baseColor = context.baseConfig.backgroundColor
        val buttonsColor = when {
            context.isDynamicTheme() && !context.isSystemInDarkMode() -> context.resources.getColor(R.color.you_status_bar_color, context.theme)
            context.isDynamicTheme() && context.isSystemInDarkMode() -> context.getSurfaceColor()
            baseColor == Color.WHITE -> context.resources.getColor(R.color.dark_grey, context.theme)
            baseColor == Color.BLACK -> context.resources.getColor(R.color.bottom_tabs_black_background, context.theme)
            else -> context.baseConfig.backgroundColor.lightenColor(4)
        }
        val textColor = buttonsColor.getContrastColor()
        val dialpadRectWrapper = binding.dialpadRectWrapper

        dialpadRectWrapper.let { wrapper ->
            val wrapperRoot = wrapper.root
            // Find CONCEPT dialpad views
            val dialpad1Holder = wrapperRoot.findViewById<View>(R.id.dialpad_1_holder)
            val dialpad2Holder = wrapperRoot.findViewById<View>(R.id.dialpad_2_holder)
            val dialpad3Holder = wrapperRoot.findViewById<View>(R.id.dialpad_3_holder)
            val dialpad4Holder = wrapperRoot.findViewById<View>(R.id.dialpad_4_holder)
            val dialpad5Holder = wrapperRoot.findViewById<View>(R.id.dialpad_5_holder)
            val dialpad6Holder = wrapperRoot.findViewById<View>(R.id.dialpad_6_holder)
            val dialpad7Holder = wrapperRoot.findViewById<View>(R.id.dialpad_7_holder)
            val dialpad8Holder = wrapperRoot.findViewById<View>(R.id.dialpad_8_holder)
            val dialpad9Holder = wrapperRoot.findViewById<View>(R.id.dialpad_9_holder)
            val dialpad0Holder = wrapperRoot.findViewById<View>(R.id.dialpad_0_holder)
            val dialpadAsteriskHolder = wrapperRoot.findViewById<View>(R.id.dialpad_asterisk_holder)
            val dialpadHashtagHolder = wrapperRoot.findViewById<View>(R.id.dialpad_hashtag_holder)
            val dialpadCallButtonHolder = wrapperRoot.findViewById<View>(R.id.dialpadCallButtonHolder)
            val dialpadClearCharHolder = wrapperRoot.findViewById<View>(R.id.dialpadClearCharHolder)
            val dialpadDownHolder = wrapperRoot.findViewById<View>(R.id.dialpadDownHolder)
            val dialpadGridHolder = wrapperRoot.findViewById<View>(R.id.dialpadGridHolder)
            val dialpadGridWrapper = wrapperRoot.findViewById<View>(R.id.dialpad_grid_wrapper)

            val dialpad1 = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_1)
            val dialpad2 = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_2)
            val dialpad3 = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_3)
            val dialpad4 = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_4)
            val dialpad5 = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_5)
            val dialpad6 = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_6)
            val dialpad7 = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_7)
            val dialpad8 = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_8)
            val dialpad9 = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_9)
            val dialpad0 = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_0)
            val dialpadAsterisk = wrapperRoot.findViewById<ImageView>(R.id.dialpad_asterisk)
            val dialpadHashtag = wrapperRoot.findViewById<ImageView>(R.id.dialpad_hashtag)
            val dialpadVoicemail = wrapperRoot.findViewById<ImageView>(R.id.dialpadVoicemail)
            val dialpadCallIcon = wrapperRoot.findViewById<ImageView>(R.id.dialpadCallIcon)
            val dialpadDown = wrapperRoot.findViewById<ImageView>(R.id.dialpadDown)
            val dialpadClearChar = wrapperRoot.findViewById<ImageView>(R.id.dialpadClearChar)

            val dialpad1Letters = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_1_letters)
            val dialpad2Letters = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_2_letters)
            val dialpad3Letters = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_3_letters)
            val dialpad4Letters = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_4_letters)
            val dialpad5Letters = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_5_letters)
            val dialpad6Letters = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_6_letters)
            val dialpad7Letters = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_7_letters)
            val dialpad8Letters = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_8_letters)
            val dialpad9Letters = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_9_letters)
            val dialpadPlus = wrapperRoot.findViewById<android.widget.TextView>(R.id.dialpad_plus)

            // Setup letters visibility and text
            if (context.config.hideDialpadLetters) {
                arrayOf<android.widget.TextView?>(
                    dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                    dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it?.beGone()
                }
            } else {
                dialpad1Letters?.apply {
                    beInvisible()
                    setTypeface(null, context.config.dialpadSecondaryTypeface)
                }
                arrayOf<android.widget.TextView?>(
                    dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters, dialpad6Letters,
                    dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it?.beVisible()
                    it?.setTypeface(null, context.config.dialpadSecondaryTypeface)
                }

                val langPref = context.config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref != null && langPref != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
                    val lang = if (isAutoLang) langLocale else langPref
                    dialpad1Letters?.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad2Letters?.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad3Letters?.text = DialpadT9.getLettersForNumber(3, lang) + "\nDEF"
                    dialpad4Letters?.text = DialpadT9.getLettersForNumber(4, lang) + "\nGHI"
                    dialpad5Letters?.text = DialpadT9.getLettersForNumber(5, lang) + "\nJKL"
                    dialpad6Letters?.text = DialpadT9.getLettersForNumber(6, lang) + "\nMNO"
                    dialpad7Letters?.text = DialpadT9.getLettersForNumber(7, lang) + "\nPQRS"
                    dialpad8Letters?.text = DialpadT9.getLettersForNumber(8, lang) + "\nTUV"
                    dialpad9Letters?.text = DialpadT9.getLettersForNumber(9, lang) + "\nWXYZ"

                    val fontSizeRu = context.getTextSize() - 16f
                    arrayOf<android.widget.TextView?>(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach { it?.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeRu) }
                } else {
                    val fontSize = context.getTextSize() - 8f
                    arrayOf<android.widget.TextView?>(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach { it?.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize) }
                }
            }

            // Set text colors
            arrayOf<android.widget.TextView?>(
                dialpad1, dialpad2, dialpad3, dialpad4, dialpad5,
                dialpad6, dialpad7, dialpad8, dialpad9, dialpad0,
                dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters, dialpad6Letters,
                dialpad7Letters, dialpad8Letters, dialpad9Letters, dialpadPlus
            ).forEach {
                it?.setTextColor(textColor)
            }

            // Setup button backgrounds and margins
            arrayOf<View?>(
                dialpad0Holder, dialpad1Holder, dialpad2Holder, dialpad3Holder, dialpad4Holder,
                dialpad5Holder, dialpad6Holder, dialpad7Holder, dialpad8Holder, dialpad9Holder,
                dialpadAsteriskHolder, dialpadHashtagHolder
            ).forEach {
                it?.background = ResourcesCompat.getDrawable(context.resources, R.drawable.button_dialpad_background, context.theme)
                it?.background?.mutate()?.setColorFilter(buttonsColor, android.graphics.PorterDuff.Mode.SRC_IN)
                it?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    val margin = context.pixels(R.dimen.one_dp).toInt()
                    setMargins(margin, margin, margin, margin)
                }
            }

            // Setup icon colors
            arrayOf<ImageView?>(
                dialpadAsterisk, dialpadHashtag, dialpadVoicemail
            ).forEach {
                it?.applyColorFilter(textColor)
            }

            // Setup special buttons (down, call, clear)
            arrayOf<View?>(
                dialpadDownHolder, dialpadCallButtonHolder, dialpadClearCharHolder
            ).forEach {
                it?.background = ResourcesCompat.getDrawable(context.resources, R.drawable.button_dialpad_background, context.theme)
                it?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    val margin = context.pixels(R.dimen.one_dp).toInt()
                    val marginBottom = context.pixels(R.dimen.tiny_margin).toInt()
                    setMargins(margin, margin, margin, marginBottom)
                }
            }

            dialpadGridWrapper?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                val margin = context.pixels(R.dimen.tiny_margin).toInt()
                setMargins(margin, margin, margin, margin)
            }

            // Setup call button
            val simOnePrimary = context.config.currentSIMCardIndex == 0
            val simTwoColor = if (areMultipleSIMsAvailable) {
                if (simOnePrimary) context.config.simIconsColors[2] else context.config.simIconsColors[1]
            } else context.getProperPrimaryColor()
            val drawableSecondary = if (simOnePrimary) R.drawable.ic_phone_two_vector else R.drawable.ic_phone_one_vector
            val dialpadIconColor = if (simTwoColor == Color.WHITE) simTwoColor.getContrastColor() else textColor
            val downIcon = if (areMultipleSIMsAvailable) {
                context.resources.getColoredDrawableWithColor(context, drawableSecondary, dialpadIconColor)
            } else {
                context.resources.getColoredDrawableWithColor(context, R.drawable.ic_dialpad_vector, dialpadIconColor)
            }
            dialpadDown?.setImageDrawable(downIcon)
            dialpadDownHolder?.apply {
                background?.mutate()?.setColorFilter(simTwoColor, android.graphics.PorterDuff.Mode.SRC_IN)
                setOnClickListener {
                    if (areMultipleSIMsAvailable) {
                        initCall(handleIndex = if (simOnePrimary) 1 else 0)
                    } else {
                        dialpadHide()
                    }
                    maybePerformDialpadHapticFeedback(dialpadDownHolder)
                }
                contentDescription = context.getString(
                    if (areMultipleSIMsAvailable) {
                        if (simOnePrimary) R.string.call_from_sim_2 else R.string.call_from_sim_1
                    } else {
                        val view = dialpadView()
                        if (view?.isVisible == true) R.string.hide_dialpad else R.string.show_dialpad
                    }
                )
            }

            val simOneColor = if (simOnePrimary) context.config.simIconsColors[1] else context.config.simIconsColors[2]
            val drawablePrimary = if (simOnePrimary) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
            val callIconId = if (areMultipleSIMsAvailable) drawablePrimary else R.drawable.ic_phone_vector
            val callIconColor = if (simOneColor == Color.WHITE) simOneColor.getContrastColor() else textColor
            val callIcon = context.resources.getColoredDrawableWithColor(context, callIconId, callIconColor)
            dialpadCallIcon?.setImageDrawable(callIcon)
            dialpadCallButtonHolder?.apply {
                background?.mutate()?.setColorFilter(simOneColor, android.graphics.PorterDuff.Mode.SRC_IN)
                setOnClickListener {
                    initCall(handleIndex = if (simOnePrimary || !areMultipleSIMsAvailable) 0 else 1)
                    maybePerformDialpadHapticFeedback(this)
                }
                setOnLongClickListener {
                    if (binding.dialpadInput.value.isEmpty()) {
                        val text = activity?.getTextFromClipboard()
                        binding.dialpadInput.setText(text)
                        if (!text.isNullOrEmpty()) {
                            binding.dialpadInput.setSelection(text.length)
                            binding.dialpadInput.requestFocusFromTouch()
                        }
                        true
                    } else {
                        copyNumber()
                        true
                    }
                }
                contentDescription = context.getString(
                    if (areMultipleSIMsAvailable) {
                        if (simOnePrimary) R.string.call_from_sim_1 else R.string.call_from_sim_2
                    } else R.string.call
                )
            }

            // Setup clear button
            dialpadClearCharHolder?.beVisible()
            dialpadClearCharHolder?.background?.mutate()?.setColorFilter(context.getColor(R.color.red_call), android.graphics.PorterDuff.Mode.SRC_IN)
            dialpadClearCharHolder?.setOnClickListener { clearChar(it) }
            dialpadClearCharHolder?.setOnLongClickListener { clearInput(); true }
            dialpadClearChar?.setAlpha(255) // 1f = 255
            dialpadClearChar?.applyColorFilter(textColor)

            // Setup number buttons
            setupCharClick(dialpad1Holder, '1')
            setupCharClick(dialpad2Holder, '2')
            setupCharClick(dialpad3Holder, '3')
            setupCharClick(dialpad4Holder, '4')
            setupCharClick(dialpad5Holder, '5')
            setupCharClick(dialpad6Holder, '6')
            setupCharClick(dialpad7Holder, '7')
            setupCharClick(dialpad8Holder, '8')
            setupCharClick(dialpad9Holder, '9')
            setupCharClick(dialpad0Holder, '0')
            setupCharClick(dialpadAsteriskHolder, '*')
            setupCharClick(dialpadHashtagHolder, '#')
            dialpadGridHolder?.setOnClickListener { } // Do not press between the buttons
        }
    }

    private fun updateDialpadSize() {
        val size = context.config.dialpadSize
        val view = when (context.config.dialpadStyle) {
            DIALPAD_IOS -> binding.dialpadRoundWrapper.root.findViewById<View>(R.id.dialpad_ios_wrapper)
            DIALPAD_CONCEPT -> binding.dialpadRectWrapper.root.findViewById<View>(R.id.dialpad_grid_wrapper)
            else -> dialpadGridBinding?.dialpadGridWrapper
        }

        if (view != null) {
            val dimens = if (context.config.dialpadStyle == DIALPAD_IOS) pixels(R.dimen.dialpad_ios_height) else pixels(R.dimen.dialpad_grid_height)
            view.setHeight((dimens * (size / 100f)).toInt())
        }

        ensureDialpadButtonMargins()
        
        val margin = context.config.dialpadBottomMargin
        val marginView = when (context.config.dialpadStyle) {
            DIALPAD_IOS -> binding.dialpadRoundWrapper.root.findViewById<View>(R.id.dialpadBottomMargin)
            DIALPAD_CONCEPT -> binding.dialpadRectWrapper.root.findViewById<View>(R.id.dialpadBottomMargin)
            else -> dialpadGridBinding?.dialpadBottomMargin
        }

        if (marginView != null) {
            val start = if (context.config.dialpadStyle == DIALPAD_IOS) pixels(R.dimen.dialpad_margin_bottom_ios) else pixels(R.dimen.zero)
            marginView.setHeight((start + margin).toInt())
        }
    }
    
    private fun ensureDialpadButtonMargins() {
        // Get the dialpad bottom margin view to calculate call button position
        val marginView = when (config.dialpadStyle) {
            DIALPAD_IOS -> binding.dialpadRoundWrapper.root.findViewById<View>(R.id.dialpadBottomMargin)
            DIALPAD_CONCEPT -> binding.dialpadRectWrapper.root.findViewById<View>(R.id.dialpadBottomMargin)
            else -> dialpadGridBinding?.dialpadBottomMargin
        }
        
        val parentView = binding.dialpadRoundWrapperUp.parent as? View
        
        if (marginView != null && parentView != null) {
            // Post to ensure layout is complete
            parentView.post {
                // Get the dialpad bottom margin height (this is the space at the bottom of the dialpad)
                val dialpadBottomMarginHeight = marginView.height
                
                // Calculate where the call button center would be
                // Call button is at the bottom of the dialpad content, which is at parent bottom - dialpad bottom margin
                val parentHeight = parentView.height
                val callButtonSize = pixels(R.dimen.dialpad_phone_button_size)
                val dialpadRoundWrapperUpSize = pixels(R.dimen.call_button_size)
                
                // Call button center Y = parent bottom - dialpad bottom margin - (call button size / 2)
                val callButtonCenterY = parentHeight - dialpadBottomMarginHeight - (callButtonSize / 2f)
                
                // Calculate bottom margin for dialpadRoundWrapperUp to align centers
                val bottomMargin = (parentHeight - callButtonCenterY - (dialpadRoundWrapperUpSize / 2f)).toInt().coerceAtLeast(0) + callButtonSize.toInt()
                
                binding.dialpadRoundWrapperUp.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    this.bottomMargin = bottomMargin
                }
                binding.dialpadRoundWrapperUp.requestLayout()
            }
        } else {
            // Fallback: use the configured margin if views are not available
            val margin = context.config.dialpadBottomMargin
            val start = if (context.config.dialpadStyle == DIALPAD_IOS) pixels(R.dimen.dialpad_margin_bottom_ios) else pixels(R.dimen.zero)
            val bottomMarginValue = (start + margin + margin).toInt()
            
            binding.dialpadRoundWrapperUp.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = bottomMarginValue
            }
            binding.dialpadRoundWrapperUp.requestLayout()
        }
    }

    private fun updateCallButtonSize() {
        val size = context.config.callButtonPrimarySize
        val view = dialpadGridBinding?.dialpadCallButton
        if (view != null) {
            val dimens = pixels(R.dimen.dialpad_phone_button_size)
            view.setHeightAndWidth((dimens * (size / 100f)).toInt())
            val padding = (dimens * 0.1765 * (size / 100f)).toInt()
            view.setPadding(padding, padding, padding, padding)
        }

        if (context.areMultipleSIMsAvailable()) {
            val sizeSecondary = context.config.callButtonSecondarySize
            val viewSecondary = dialpadGridBinding?.dialpadCallTwoButton
            if (viewSecondary != null) {
                val dimensSecondary = pixels(R.dimen.dialpad_button_size_small)
                viewSecondary.setHeightAndWidth((dimensSecondary * (sizeSecondary / 100f)).toInt())
                val dimens = pixels(R.dimen.dialpad_phone_button_size)
                val padding = (dimens * 0.1765 * (sizeSecondary / 100f)).toInt()
                viewSecondary.setPadding(padding, padding, padding, padding)
            }
        }
    }

    private fun refreshMenuItems() {
        binding.dialpadToolbar.menu.apply {
            val dialpadInputValue = binding.dialpadInput.value
            findItem(R.id.copy_number).isVisible = dialpadInputValue.isNotEmpty()
            findItem(R.id.web_search).isVisible = /*dialpadInputValue.isNotEmpty()*/false
//            findItem(R.id.cab_call_anonymously).isVisible = dialpadInputValue.isNotEmpty()
            findItem(R.id.clear_call_history).isVisible = context.config.showRecentCallsOnDialpad
            findItem(R.id.show_blocked_numbers).isVisible = context.config.showRecentCallsOnDialpad
            findItem(R.id.show_blocked_numbers).title =
                if (context.config.showBlockedNumbers) context.getString(R.string.hide_blocked_numbers) else context.getString(R.string.show_blocked_numbers)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        EventBus.getDefault().unregister(this)
        longPressHandler.removeCallbacksAndMessages(null)
        searchDebounceJob?.cancel()
        toneGeneratorHelper?.stopTone()
        toneGeneratorHelper = null
        fragmentScope.cancel()
        // Unregister contact observer
        unregisterContactObserver()
        // Clear caches and adapters
        cachedDialpadView = null
        cachedCollator = null
        contactsAdapter = null
        recentsAdapter = null
    }
}
