package com.android.dialer.activities

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.telecom.Call
import android.view.Menu
import android.view.MenuItem
import android.content.ActivityNotFoundException
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import androidx.core.view.updateLayoutParams
import androidx.viewpager.widget.ViewPager
import com.behaviorule.arturdumchev.library.pixels
import com.google.android.material.snackbar.Snackbar
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.dialogs.RadioGroupDialog
import eightbitlab.com.blurview.BlurTarget
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.securebox.SecureBoxHelper
import com.goodwy.commons.securebox.SecureBoxCall
import com.goodwy.commons.securebox.SecureBoxContact
import com.android.dialer.BuildConfig
import com.android.dialer.R
import com.android.dialer.adapters.ViewPagerAdapter
import com.android.dialer.databinding.ActivityMainBinding
import com.android.dialer.dialogs.ChangeSortingDialog
import com.android.dialer.dialogs.FilterContactSourcesDialog
import com.android.dialer.extensions.*
import com.android.dialer.fragments.ContactsFragment
import com.android.dialer.fragments.DialpadFragment
import com.android.dialer.fragments.FavoritesFragment
import com.android.dialer.fragments.MyViewPagerFragment
import com.android.dialer.fragments.RecentsFragment
import com.android.dialer.activities.SettingsDialpadActivity
import com.android.dialer.helpers.*
import com.android.dialer.interfaces.RefreshItemsListener
import com.android.dialer.models.AudioRoute
import com.android.dialer.models.Events
import com.android.dialer.models.RecentCall
import com.goodwy.commons.views.TwoFingerSlideGestureDetector
import com.mikhaellopez.rxanimation.RxAnimation
import com.mikhaellopez.rxanimation.fadeIn
import com.mikhaellopez.rxanimation.fadeOut
import com.mikhaellopez.rxanimation.scale
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.InputStreamReader

class MainActivity : SimpleActivity() {
    override var isSearchBarEnabled = true

    private val binding by viewBinding(ActivityMainBinding::inflate)

    private var launchedDialer = false
    private var isSearchOpen = false
    private var storedShowTabs = 0
    private var storedFontSize = 0
    private var searchQuery = ""
    private var storedStartNameWithSurname = false
    private var storedShowPhoneNumbers = false
    private var storedBackgroundColor = 0
    private var currentOldScrollY = 0
    private var cachedFavorites = mutableListOf<Contact>()
    private var storedContactShortcuts = mutableListOf<Contact>()
    private var isSpeechToTextAvailable = false
    
    // Cached fragment references to avoid repeated findViewById calls
    private var cachedContactsFragment: ContactsFragment? = null
    private var cachedFavoritesFragment: FavoritesFragment? = null
    private var cachedRecentsFragment: RecentsFragment? = null
    private var cachedDialpadFragment: DialpadFragment? = null

    // Two-finger swipe detection for secure box
    private lateinit var twoFingerSlideGestureDetector: TwoFingerSlideGestureDetector
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Track menu height animation state for smooth transitions
    private var menuHeightAnimator: android.animation.ValueAnimator? = null
    private var currentMenuHeight: Int = -1
    private var fullMenuHeight: Int = -1
    
    // Secure box / Private space integration
    private val PRIVATE_SPACE_PACKAGE_NAME = "chonha.get.secret.number"
    private var unlockedPrivateSpaceCode: Int? = null
    private var pendingCallToEncrypt: RecentCall? = null
    private var pendingContactToEncrypt: Contact? = null
    private val isInPrivateMode: Boolean
        get() = unlockedPrivateSpaceCode != null

    @SuppressLint("MissingSuperCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()
        storeStateVariables()
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainTabsHolder, binding.mainDialpadButton))

        EventBus.getDefault().register(this)
        launchedDialer = savedInstanceState?.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH) ?: false
        val properBackgroundColor = getProperBackgroundColor()

        if (isDefaultDialer()) {
            checkContactPermissions()

            if (!config.wasOverlaySnackbarConfirmed && !Settings.canDrawOverlays(this)) {
                val snackbar = Snackbar.make(
                    binding.mainHolder,
                    R.string.allow_displaying_over_other_apps,
                    Snackbar.LENGTH_INDEFINITE
                ).setAction(R.string.ok) {
                    config.wasOverlaySnackbarConfirmed = true
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }

                snackbar.setBackgroundTint(properBackgroundColor.darkenColor())
                val properTextColor = getProperTextColor()
                snackbar.setTextColor(properTextColor)
                snackbar.setActionTextColor(properTextColor)
                val snackBarView: View = snackbar.view
                snackBarView.translationY = -pixels(R.dimen.snackbar_bottom_margin)
                snackbar.show()
            }

            handleFullScreenNotificationsPermission { granted ->
                if (!granted) {
                    toast(com.goodwy.commons.R.string.notifications_disabled)
                } else {
//                    checkWhatsNewDialog()
                }
            }
        } else {
            launchSetDefaultDialerIntent()
        }

        if (isQPlus() && (baseConfig.blockUnknownNumbers || baseConfig.blockHiddenNumbers)) {
            setDefaultCallerIdApp()
        }

        binding.mainMenu.apply {
            updateTitle(getAppLauncherName())
            searchBeVisibleIf(false) //hide top search bar
        }

        setupTabs()
        Contact.sorting = config.sorting

        setupSecondaryLanguage()

        // At the first launch, enable the general blocking if at least one blocking was enabled
        if (config.initCallBlockingSetup) {
            if (getBlockedNumbers().isNotEmpty() || baseConfig.blockUnknownNumbers || baseConfig.blockHiddenNumbers) {
                baseConfig.blockingEnabled = true
            }
            config.initCallBlockingSetup = false
        }

        CallManager.addListener(callCallback)
        binding.mainCallButton.setOnClickListener { startActivity(Intent(this, CallActivity::class.java)) }
        
        // Set initial button state based on current call state
        updateState()
        
        // Set up two-finger swipe detection on the main holder
        setupTwoFingerSwipeDetection()
    }
    
    private fun setupTwoFingerSwipeDetection() {
        twoFingerSlideGestureDetector = TwoFingerSlideGestureDetector(
            this,
            object : TwoFingerSlideGestureDetector.OnTwoFingerSlideGestureListener {
                override fun onTwoFingerSlide(
                    firstFingerX: Float,
                    firstFingerY: Float,
                    secondFingerX: Float,
                    secondFingerY: Float,
                    avgDeltaX: Float,
                    avgDeltaY: Float,
                    avgDistance: Float
                ) {
                    // If already in private mode, show secure box with current cipher
                    if (isInPrivateMode && unlockedPrivateSpaceCode != null) {
                        unlockSecureBoxWithCipher(unlockedPrivateSpaceCode!!)
                    } else {
                        // Launch private space app to get cipher number
                        handlePrivateSpaceSwipe()
                    }
                }
            }
        )
    }
    
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Handle 2 finger gestures globally - intercept all 2-finger touches
        if (ev.pointerCount == 2) {
            // Pass event to gesture detector and consume to prevent child views from handling it
            twoFingerSlideGestureDetector.onTouchEvent(ev)
            return true
        }

        // One finger gestures pass to default handling
        return super.dispatchTouchEvent(ev)
    }
    
    private fun handlePrivateSpaceSwipe() {
        val launchIntent = Intent(PRIVATE_SPACE_PACKAGE_NAME)
        
        // Start private space app and expect a result back
        try {
            startPrivateSpaceForUnlock.launch(launchIntent)
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            toast("Private space app not found", Toast.LENGTH_SHORT)
        }
    }
    
    private val startPrivateSpaceForUnlock = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        
        // Expect private code as extra
        val cipher = result.data?.getIntExtra("secret_number", -1)
            ?.takeIf { it > -1 }
            ?: return@registerForActivityResult
        
        unlockedPrivateSpaceCode = cipher
        SecureBoxHelper.unlockSecureBox()
        
        // Show secure box with the unlocked cipher
        unlockSecureBoxWithCipher(cipher)
    }
    
    private val startPrivateSpaceForEncrypt = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        
        // Expect private code as extra
        val cipher = result.data?.getIntExtra("secret_number", -1)
            ?.takeIf { it > -1 }
            ?: return@registerForActivityResult
        
        // Encrypt pending call if exists
        pendingCallToEncrypt?.let { call ->
            pendingCallToEncrypt = null
            encryptCall(call, cipher)
        }
        
        // Encrypt pending contact if exists
        pendingContactToEncrypt?.let { contact ->
            pendingContactToEncrypt = null
            encryptContact(contact, cipher)
        }
    }
    
    private fun unlockSecureBoxWithCipher(cipherNumber: Int) {
        // Directly access secure box cipher number without unlock check
        val secureBoxHelper = SecureBoxHelper(this)
        val (calls, contacts) = secureBoxHelper.getSecureBoxByCipherNumber(cipherNumber)
        
        // Navigate to RecentsFragment and show secure box contents filtered by cipher number
        showSecureBoxInRecents(calls, contacts, cipherNumber)
    }
    
    /**
     * Encrypt a call by adding it to secure box with specified cipher number
     */
    fun encryptCall(call: RecentCall, cipherNumber: Int) {
        val secureBoxHelper = SecureBoxHelper(this)
        secureBoxHelper.addCallToSecureBox(call.id, cipherNumber)
        
        // Refresh UI
        mainHandler.post {
            getRecentsFragment()?.refreshItems()
        }
    }
    
    /**
     * Decrypt a call by removing it from secure box
     */
    fun decryptCall(call: RecentCall) {
        val secureBoxHelper = SecureBoxHelper(this)
        secureBoxHelper.removeCallFromSecureBox(call.id)
        
        // Refresh UI
        mainHandler.post {
            getRecentsFragment()?.refreshItems()
        }
    }
    
    /**
     * Encrypt a contact by adding it to secure box with specified cipher number
     */
    fun encryptContact(contact: Contact, cipherNumber: Int) {
        val secureBoxHelper = SecureBoxHelper(this)
        secureBoxHelper.addContactToSecureBox(contact.id, cipherNumber)
        
        // Refresh UI
        mainHandler.post {
            getContactsFragment()?.refreshItems()
            getFavoritesFragment()?.refreshItems()
        }
    }
    
    /**
     * Decrypt a contact by removing it from secure box
     */
    fun decryptContact(contact: Contact) {
        val secureBoxHelper = SecureBoxHelper(this)
        secureBoxHelper.removeContactFromSecureBox(contact.id)
        
        // Refresh UI
        mainHandler.post {
            getContactsFragment()?.refreshItems()
            getFavoritesFragment()?.refreshItems()
        }
    }
    
    /**
     * Launch private space app to encrypt a call
     */
    fun encryptCallWithPrivateSpace(call: RecentCall) {
        // Save current call that must be encrypted after user picks cipher
        pendingCallToEncrypt = call
        
        // Launch PrivateSpaceApp to get a new private space code
        val launchIntent = Intent(PRIVATE_SPACE_PACKAGE_NAME)
        
        // Start private space app and expect a result back
        try {
            startPrivateSpaceForEncrypt.launch(launchIntent)
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            toast("Private space app not found", Toast.LENGTH_SHORT)
        }
    }
    
    /**
     * Launch private space app to encrypt a contact
     */
    fun encryptContactWithPrivateSpace(contact: Contact) {
        // Save current contact that must be encrypted after user picks cipher
        pendingContactToEncrypt = contact
        
        // Launch PrivateSpaceApp to get a new private space code
        val launchIntent = Intent(PRIVATE_SPACE_PACKAGE_NAME)
        
        // Start private space app and expect a result back
        try {
            startPrivateSpaceForEncrypt.launch(launchIntent)
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            toast("Private space app not found", Toast.LENGTH_SHORT)
        }
    }
    
    /**
     * Check if a call is in secure box
     */
    fun isCallInSecureBox(callId: Int): Boolean {
        val secureBoxHelper = SecureBoxHelper(this)
        return secureBoxHelper.isCallInSecureBox(callId)
    }
    
    /**
     * Check if a contact is in secure box
     */
    fun isContactInSecureBox(contactId: Int): Boolean {
        val secureBoxHelper = SecureBoxHelper(this)
        return secureBoxHelper.isContactInSecureBox(contactId)
    }
    
    /**
     * Exit private mode
     */
    private fun exitPrivateMode() {
        unlockedPrivateSpaceCode = null
        SecureBoxHelper.lockSecureBox()
        
        // Refresh UI
        mainHandler.post {
            refreshFragments()
        }
    }
    
    private fun showSecureBoxInRecents(calls: List<SecureBoxCall>, contacts: List<SecureBoxContact>, cipherNumber: Int) {
        val totalItems = calls.size + contacts.size
        
        if (totalItems == 0) {
            toast("Secure Box Cipher $cipherNumber is empty", Toast.LENGTH_SHORT)
            return
        }
        
        // Navigate to RecentsFragment (call history tab)
        val recentsTabIndex = getAllFragments().indexOfFirst { it is RecentsFragment }
        if (recentsTabIndex >= 0) {
            binding.viewPager.currentItem = recentsTabIndex
            // Pass secure box data to RecentsFragment with cipher number
            getRecentsFragment()?.showSecureBoxByCipherNumber(calls, contacts, cipherNumber)
        } else {
            toast("Secure Box Cipher $cipherNumber: ${calls.size} calls, ${contacts.size} contacts", Toast.LENGTH_LONG)
        }
    }

    override fun onResume() {
        super.onResume()
        if (storedShowTabs != config.showTabs || storedShowPhoneNumbers != config.showPhoneNumbers) {
            System.exit(0)
            return
        }

        @SuppressLint("UnsafeIntentLaunch")
        if (config.needRestart || storedBackgroundColor != getProperBackgroundColor()) {
            config.lastUsedViewPagerPage = 0
            finish()
            startActivity(intent)
            return
        }

        // Cache color calculations to avoid repeated calls
        val properTextColor = getProperTextColor()
        val properPrimaryColor = getProperPrimaryColor()
        val properBackgroundColor = getProperBackgroundColor()
        val properAccentColor = getProperAccentColor()
        val iconTintColor = properPrimaryColor.getContrastColor()
        
        // Setup buttons
        setupButtons(iconTintColor, properBackgroundColor)

        updateTextColors(binding.mainHolder)
        binding.mainMenu.updateColors(
            background = getStartRequiredStatusBarColor(),
            scrollOffset = scrollingView?.computeVerticalScrollOffset() ?: 0
        )
        binding.mainMenu.requireCustomToolbar().updateSearchColors()

        // Handle name with surname change
        handleStartNameWithSurnameChange()

        if (!binding.mainMenu.isSearchOpen) {
            refreshItems(true)
        }

        // Cache fragments list to avoid multiple getAllFragments() calls
        val allFragments = if (binding.viewPager.adapter != null) getAllFragments() else emptyList()
        
        // Batch fragment operations
        allFragments.forEach { fragment ->
            fragment?.setupColors(properTextColor, properPrimaryColor, properAccentColor)
            if (storedFontSize != config.fontSize) {
                fragment?.fontSizeChanged()
            }
        }
        
        if (storedFontSize != config.fontSize) {
            storedFontSize = config.fontSize
        }

        invalidateOptionsMenu()
        setupViewPagerAnimation()
        
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else properBackgroundColor
        if (useSurfaceColor) binding.mainHolder.setBackgroundColor(getSurfaceColor())
        allFragments.forEach { it?.setBackgroundColor(backgroundColor) }
        
        handleCurrentFragment()
        checkShortcuts()
        
        // Initialize full menu height using ViewTreeObserver for accurate measurement
        if (fullMenuHeight == -1) {
            val currentFragment = getCurrentFragment()
            if (currentFragment !is DialpadFragment && binding.mainMenu.height == 0) {
                // Wait for layout to complete
                binding.mainMenu.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        binding.mainMenu.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        if (fullMenuHeight == -1 && binding.mainMenu.height > 0) {
                            fullMenuHeight = binding.mainMenu.height
                            if (currentMenuHeight == -1) {
                                currentMenuHeight = fullMenuHeight
                            }
                        }
                    }
                })
            } else if (binding.mainMenu.height > 0) {
                // Already laid out, use current height
                fullMenuHeight = binding.mainMenu.height
                if (currentMenuHeight == -1) {
                    currentMenuHeight = fullMenuHeight
                }
            }
        }
        
        // Update call button state when resuming to ensure correct visibility
        updateState()
    }
    
    private fun setupButtons(iconTintColor: Int, backgroundColor: Int) {
        binding.mainDialpadButton.apply {
            setIcon(R.drawable.ic_dialpad_vector)
            setIconTint(iconTintColor)
            setBackgroundColor(backgroundColor)
            beGone() // Always hide the dialpad button
        }
        
        binding.mainCallButton.apply {
            setIcon(R.drawable.ic_phone_vector)
            setIconTint(iconTintColor)
            setBackgroundColor(backgroundColor)
        }
    }
    
    private fun handleStartNameWithSurnameChange() {
        val configStartNameWithSurname = config.startNameWithSurname
        if (storedStartNameWithSurname != configStartNameWithSurname) {
            getContactsFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            getFavoritesFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            storedStartNameWithSurname = configStartNameWithSurname
        }
    }
    
    private fun setupViewPagerAnimation() {
        val animation = when (config.screenSlideAnimation) {
            1 -> ZoomOutPageTransformer()
            2 -> DepthPageTransformer()
            else -> null
        }
        binding.viewPager.setPageTransformer(true, animation)
        binding.viewPager.setPagingEnabled(!config.useSwipeToAction)
    }
    
    private fun handleCurrentFragment() {
        val currentFragment = getCurrentFragment()
        if (currentFragment is RecentsFragment) clearMissedCalls()

        // Hide main menu when dialpad fragment is shown
        if (currentFragment is DialpadFragment) {
            setMainMenuHeight(0, animated = true)
            EventBus.getDefault().post(Events.RefreshDialpadSettings)
        } else {
            setMainMenuHeight(null, animated = true)
        }

        // Call onFragmentResume on fragments to register contact observers
        getContactsFragment()?.onFragmentResume()
        getFavoritesFragment()?.onFragmentResume()
        getDialpadFragment()?.onFragmentResume()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
        
        // Call onFragmentPause on fragments to unregister contact observers
        getContactsFragment()?.onFragmentPause()
        getFavoritesFragment()?.onFragmentPause()
        getDialpadFragment()?.onFragmentPause()
    }

    private fun storeStateVariables() {
        config.apply {
            storedShowTabs = showTabs
            storedStartNameWithSurname = startNameWithSurname
            storedShowPhoneNumbers = showPhoneNumbers
            storedFontSize = fontSize
            needRestart = false
        }
        storedBackgroundColor = getProperBackgroundColor()
    }

    private fun setMainMenuHeight(height: Int?, animated: Boolean = true) {
        binding.mainMenu.apply {
            // Get the actual current height from the view
            val actualCurrentHeight = if (this.height > 0) this.height else {
                if (layoutParams.height > 0 && layoutParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    layoutParams.height
                } else {
                    -1
                }
            }
            
            // Initialize current height tracking
            if (currentMenuHeight == -1 && actualCurrentHeight > 0) {
                currentMenuHeight = actualCurrentHeight
            }
            
            // Determine target height
            val targetHeight = if (height != null) {
                height
            } else {
                // For wrap_content, use stored full height or get it from the view
                if (fullMenuHeight > 0) {
                    fullMenuHeight
                } else if (actualCurrentHeight > 0) {
                    // Use current height if available
                    actualCurrentHeight.also { fullMenuHeight = it }
                } else {
                    // Fallback: use a reasonable default or measure after layout
                    post {
                        if (fullMenuHeight == -1) {
                            // Wait for layout to complete
                            viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                                override fun onGlobalLayout() {
                                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                                    if (fullMenuHeight == -1 && this@apply.height > 0) {
                                        fullMenuHeight = this@apply.height
                                        if (currentMenuHeight == -1) {
                                            currentMenuHeight = fullMenuHeight
                                        }
                                    }
                                }
                            })
                        }
                    }
                    // Return current height or 0 as fallback
                    actualCurrentHeight.takeIf { it > 0 } ?: 0
                }
            }
            
            // Skip if already at target height
            if (currentMenuHeight == targetHeight && currentMenuHeight > 0) {
                return
            }
            
            // Cancel any ongoing animation
            menuHeightAnimator?.cancel()
            
            if (animated && targetHeight > 0) {
                // Animate smoothly
                beVisible()
                val menuView = this // Capture view reference for nested lambdas
                val startHeight = if (currentMenuHeight > 0) currentMenuHeight else actualCurrentHeight.takeIf { it > 0 } ?: targetHeight
                menuHeightAnimator = android.animation.ValueAnimator.ofInt(startHeight, targetHeight).apply {
                    duration = 300
                    interpolator = android.view.animation.DecelerateInterpolator(1.5f)
                    addUpdateListener { animator ->
                        val animatedHeight = animator.animatedValue as Int
                        currentMenuHeight = animatedHeight
                        menuView.updateLayoutParams<ViewGroup.LayoutParams> {
                            this.height = animatedHeight.coerceAtLeast(0)
                        }
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            currentMenuHeight = targetHeight
                            if (height == null) {
                                // Reset to wrap_content after animation completes
                                menuView.post {
                                    menuView.updateLayoutParams<ViewGroup.LayoutParams> {
                                        this.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                    }
                                    // Update stored height after layout
                                    menuView.post {
                                        if (menuView.height > 0) {
                                            fullMenuHeight = menuView.height
                                            currentMenuHeight = fullMenuHeight
                                        }
                                    }
                                }
                            }
                        }
                    })
                    start()
                }
            } else {
                // Set directly without animation (for initial setup or immediate changes)
                beVisible()
                updateLayoutParams<ViewGroup.LayoutParams> {
                    this.height = if (height != null) height else ViewGroup.LayoutParams.WRAP_CONTENT
                }
                // Update tracking after layout
                if (height == null) {
                    post {
                        if (this.height > 0) {
                            fullMenuHeight = this.height
                            currentMenuHeight = fullMenuHeight
                        }
                    }
                } else {
                    currentMenuHeight = height
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        // we don't really care about the result, the app can work without being the default Dialer too
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER) {
            checkContactPermissions()
        } else if (requestCode == REQUEST_CODE_SET_DEFAULT_CALLER_ID && resultCode != RESULT_OK) {
            toast(R.string.must_make_default_caller_id_app, length = Toast.LENGTH_LONG)
            baseConfig.blockUnknownNumbers = false
            baseConfig.blockHiddenNumbers = false
        } else if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultCode == RESULT_OK) {
            resultData?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { speechToText ->
                if (speechToText.isNotEmpty()) {
                    binding.mainMenu.setText(speechToText)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, launchedDialer)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshItems()
    }

    override fun onBackPressedCompat(): Boolean {
        // 1) Exit private mode first
        if (isInPrivateMode) {
            exitPrivateMode()
            return true
        }
        
        // 2) Handle search
        return when {
            binding.mainMenu.isSearchOpen -> {
                binding.mainMenu.closeSearch()
                true
            }

            isSearchOpen -> {
                val customToolbar = binding.mainMenu.requireCustomToolbar()
                if (customToolbar.isSearchExpanded) {
                    customToolbar.collapseSearch()
                    isSearchOpen = false
                    getCurrentFragment()?.onSearchClosed()
                    searchQuery = ""
                }
                true
            }

            else -> {
                false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CallManager.removeListener(callCallback)
        EventBus.getDefault().unregister(this)
        clearFragmentCache()
        // Cancel any ongoing animations
        menuHeightAnimator?.cancel()
        menuHeightAnimator = null
    }

    private fun refreshMenuItems() {
        val currentFragment = getCurrentFragment()
        val getRecentsFragment = getRecentsFragment()
        val getFavoritesFragment = getFavoritesFragment()
        val dialpadFragment = getDialpadFragment()
        binding.mainMenu.requireCustomToolbar().menu.apply {
            findItem(R.id.clear_call_history).isVisible = currentFragment == getRecentsFragment
            findItem(R.id.sort).isVisible = currentFragment != getRecentsFragment && currentFragment != dialpadFragment
            findItem(R.id.filter).isVisible = currentFragment != dialpadFragment
            findItem(R.id.create_new_contact).isVisible = currentFragment == getContactsFragment()
            findItem(R.id.change_view_type).isVisible = currentFragment == getFavoritesFragment
            findItem(R.id.column_count).isVisible = currentFragment == getFavoritesFragment && config.viewType == VIEW_TYPE_GRID
            findItem(R.id.show_blocked_numbers).isVisible = currentFragment == getRecentsFragment
            findItem(R.id.show_blocked_numbers).title =
                if (config.showBlockedNumbers) getString(R.string.hide_blocked_numbers) else getString(R.string.show_blocked_numbers)
            // Show select menu item only for non-dialpad fragments that have items
            val contactsFragment = getContactsFragment()
            val hasItems = when (currentFragment) {
                getRecentsFragment -> {
                    currentFragment?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recents_list)?.adapter?.itemCount ?: 0 > 0
                }
                getFavoritesFragment, contactsFragment -> {
                    currentFragment?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.fragment_list)?.adapter?.itemCount ?: 0 > 0
                }
                else -> false
            }
            findItem(R.id.select).isVisible = currentFragment != null && 
                currentFragment != dialpadFragment && hasItems
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
            requireCustomToolbar().inflateMenu(R.menu.menu)
//            toggleHideOnScroll(false)
            /*if (config.bottomNavigationBar) {
                if (baseConfig.useSpeechToText) {
                    isSpeechToTextAvailable = isSpeechToTextAvailable()
                    showSpeechToText = isSpeechToTextAvailable
                }

                setupMenu()

                onSpeechToTextClickListener = {
                    speechToText()
                }

                onSearchClosedListener = {
                    getAllFragments().forEach {
                        it?.onSearchQueryChanged("")
                    }
                }

                onSearchTextChangedListener = { text ->
                    getCurrentFragment()?.onSearchQueryChanged(text)
                    clearSearch()
                }
            } else*/ setupCustomSearch(requireCustomToolbar().menu)

            requireCustomToolbar().setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.show_blocked_numbers -> {
                        val dialpadFragment = getDialpadFragment()
                        if (dialpadFragment != null) {
                            dialpadFragment.showBlockedNumbers()
                        } else {
                            showBlockedNumbers()
                        }
                    }
                    R.id.clear_call_history -> {
                        val dialpadFragment = getDialpadFragment()
                        if (dialpadFragment != null) {
                            dialpadFragment.clearCallHistory()
                        } else {
                            clearCallHistory()
                        }
                    }
                    R.id.create_new_contact -> launchCreateNewContactIntent()
                    R.id.sort -> showSortingDialog(showCustomSorting = getCurrentFragment() is FavoritesFragment)
                    R.id.filter -> {
                        val recentsFragment = getRecentsFragment()
                        if (getCurrentFragment() == recentsFragment) {
                            recentsFragment?.showCallTypeFilterDialog()
                        } else {
                            showFilterDialog()
                        }
                    }
                    R.id.settings -> launchSettings()
//                    R.id.about -> launchAbout()
                    R.id.change_view_type -> changeViewType()
                    R.id.column_count -> changeColumnCount()
                    R.id.select -> getCurrentFragment()?.startActMode()
                    else -> return@setOnMenuItemClickListener false
                }
                return@setOnMenuItemClickListener true
            }
        }
    }

    private fun changeColumnCount() {
        val items = (1..CONTACTS_GRID_MAX_COLUMNS_COUNT).map { i ->
            RadioItem(i, resources.getQuantityString(R.plurals.column_counts, i, i))
        }

        val currentColumnCount = config.contactsGridColumnCount
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        RadioGroupDialog(this, ArrayList(items), currentColumnCount, R.string.column_count, blurTarget = blurTarget) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.contactsGridColumnCount = newColumnCount
                getFavoritesFragment()?.columnCountChanged()
            }
        }
    }

    private fun changeViewType() {
        config.viewType = if (config.viewType == VIEW_TYPE_LIST) VIEW_TYPE_GRID else VIEW_TYPE_LIST
        refreshMenuItems()
        getFavoritesFragment()?.refreshItems()
    }

    private fun checkContactPermissions() {
        handlePermission(PERMISSION_READ_CONTACTS) {
            initFragments()
        }
    }

    private fun setupCustomSearch(menu: Menu) {
        updateMenuItemColors(menu)

        val customToolbar = binding.mainMenu.requireCustomToolbar()
        
        // Update search colors to match theme
        customToolbar.updateSearchColors()
        
        // Setup search text change listener
        customToolbar.setOnSearchTextChangedListener { newText ->
            if (customToolbar.isSearchExpanded) {
                searchQuery = newText
                getCurrentFragment()?.onSearchQueryChanged(newText)
            }
        }
        
        // Setup search back button click listener
        customToolbar.setOnSearchBackClickListener {
            // Search collapse is handled by CustomToolbar, just update our state
            isSearchOpen = false
            getCurrentFragment()?.onSearchClosed()
            searchQuery = ""
        }
    }
    
    private fun toggleCustomSearchBar() {
        val customToolbar = binding.mainMenu.requireCustomToolbar()
        if (customToolbar.isSearchExpanded) {
            customToolbar.collapseSearch()
            isSearchOpen = false
            getCurrentFragment()?.onSearchClosed()
            searchQuery = ""
        } else {
            customToolbar.expandSearch()
            isSearchOpen = true
        }
    }

    private fun showBlockedNumbers() {
        config.showBlockedNumbers = !config.showBlockedNumbers
        binding.mainMenu.requireCustomToolbar().menu.findItem(R.id.show_blocked_numbers).title =
            if (config.showBlockedNumbers) getString(R.string.hide_blocked_numbers) else getString(R.string.show_blocked_numbers)
        config.needUpdateRecents = true
        mainHandler.post {
            getRecentsFragment()?.refreshItems()
        }
    }

    private fun clearCallHistory() {
        val confirmationText = "${getString(R.string.clear_history_confirmation)}\n\n${getString(R.string.cannot_be_undone)}"
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        ConfirmationDialog(this, confirmationText, blurTarget = blurTarget) {
            RecentsHelper(this).removeAllRecentCalls(this) {
                mainHandler.post {
                    // Force refresh with needUpdate to ensure UI updates immediately
                    getRecentsFragment()?.refreshItems(invalidate = true, needUpdate = true)
                    // Also update the dialpad fragment if it exists
                    getDialpadFragment()?.refreshItems(needUpdate = true)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        val iconColor = getProperPrimaryColor()
        if (config.lastHandledShortcutColor != iconColor) {
            val launchDialpad = getLaunchDialpadShortcut(iconColor)

            try {
                shortcutManager.dynamicShortcuts = listOf(launchDialpad)
                config.lastHandledShortcutColor = iconColor
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getLaunchDialpadShortcut(iconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.dialpad)
        val drawable = AppCompatResources.getDrawable(this, R.drawable.shortcut_dialpad)
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_dialpad_background).applyColorFilter(iconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, DialpadActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "launch_dialpad")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    private fun createContactShortcuts() {
        ensureBackgroundThread {
            if (isRPlus() && shortcutManager.isRequestPinShortcutSupported) {
                val starred = cachedFavorites.filter { it.phoneNumbers.isNotEmpty() }.take(3)
                if (storedContactShortcuts != starred) {
                    val allShortcuts = shortcutManager.dynamicShortcuts.mapNotNull { shortcut ->
                        if (shortcut.id != "launch_dialpad") shortcut.id else null
                    }
                    shortcutManager.removeDynamicShortcuts(allShortcuts)

                    storedContactShortcuts.clear()
                    storedContactShortcuts.addAll(starred)

                    starred.reversed().forEach { contact ->
                        val name = contact.getNameToDisplay()
                        getShortcutImageNeedBackground(contact.photoUri, name) { image ->
                            this.runOnUiThread {
                                val number = if (contact.phoneNumbers.size == 1) {
                                    contact.phoneNumbers[0].normalizedNumber
                                } else {
                                    contact.phoneNumbers.firstOrNull { it.isPrimary }?.normalizedNumber
                                }

                                if (number != null) {
                                    this.handlePermission(PERMISSION_CALL_PHONE) { hasPermission ->
                                        val action = if (hasPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
                                        val intent = Intent(action).apply {
                                            data = Uri.fromParts("tel", number, null)
                                            putExtra(IS_RIGHT_APP, BuildConfig.RIGHT_APP_KEY)
                                        }

                                        val shortcut = ShortcutInfo.Builder(this, "contact_${contact.id}")
                                            .setShortLabel(name)
                                            .setIcon(Icon.createWithAdaptiveBitmap(image))
                                            .setIntent(intent)
                                            .build()
                                        this.shortcutManager.pushDynamicShortcut(shortcut)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

//    private fun setupTabColors() {
//        // bottom tab bar
//        if (config.bottomNavigationBar) {
//            val bottomBarColor =
//                if (isDynamicTheme() && !isSystemInDarkMode()) getColoredMaterialStatusBarColor()
//                else getSurfaceColor()
//            binding.mainTabsHolder.setBackgroundColor(Color.TRANSPARENT)
////            if (binding.mainTabsHolder.tabCount != 1) updateNavigationBarColor(bottomBarColor)
////            else {
////                // TODO TRANSPARENT Navigation Bar
////                setWindowTransparency(true) { _, bottomNavigationBarSize, leftNavigationBarSize, rightNavigationBarSize ->
////                    binding.mainCoordinator.setPadding(leftNavigationBarSize, 0, rightNavigationBarSize, 0)
////                    binding.mainDialpadButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
////                        setMargins(0, 0, 0, bottomNavigationBarSize + pixels(R.dimen.activity_margin).toInt())
////                    }
////                }
////            }
//        }
//    }

    // Tab configuration data class for better organization
    private data class TabConfig(
        val iconRes: Int,
        val labelRes: Int
    )
    
    private val tabConfigs = arrayOf(
        TabConfig(R.drawable.ic_star_vector, R.string.favorites_tab),
        TabConfig(R.drawable.ic_clock_filled_vector, R.string.recents),
        TabConfig(R.drawable.ic_person_rounded, R.string.contacts_tab),
        TabConfig(R.drawable.ic_dialpad_vector, R.string.dialpad)
    )

    @Suppress("DEPRECATION")
    private fun initFragments() {
        binding.viewPager.offscreenPageLimit = 2
        
        // Track previous position for smooth menu height transitions
        var previousPosition = binding.viewPager.currentItem
        var previousFragment: MyViewPagerFragment<*>? = null
        
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {
                // When scrolling ends, ensure final state is correct
                if (state == ViewPager.SCROLL_STATE_IDLE) {
                    val currentFragment = getCurrentFragment()
                    // Use a small delay to ensure smooth transition completes
                    binding.viewPager.postDelayed({
                        if (currentFragment is DialpadFragment) {
                            setMainMenuHeight(0, animated = false) // No animation needed, already animated during scroll
                        } else {
                            setMainMenuHeight(null, animated = false) // No animation needed, already animated during scroll
                        }
                    }, 50)
                }
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                if (config.bottomNavigationBar && config.changeColourTopBar) scrollChange()
                
                // Smoothly animate menu height during scroll for dialpad transitions
                val fragments = getAllFragments()
                val currentFragment = fragments.getOrNull(position)
                val nextFragment = fragments.getOrNull(position + 1)
                
                // Determine if we're transitioning to/from dialpad
                val currentIsDialpad = currentFragment is DialpadFragment
                val nextIsDialpad = nextFragment is DialpadFragment
                
                if (currentIsDialpad != nextIsDialpad && nextFragment != null) {
                    // Cancel any ongoing animation to avoid conflicts
                    menuHeightAnimator?.cancel()
                    
                    // Get full menu height if not stored - use actual view height
                    if (fullMenuHeight == -1) {
                        // Try to get from current height if menu is visible and not on dialpad
                        val currentHeight = binding.mainMenu.height
                        if (currentHeight > 0 && !currentIsDialpad) {
                            fullMenuHeight = currentHeight
                        } else {
                            // If we're transitioning from dialpad, we need to get the natural height
                            // Use the stored currentMenuHeight if available, otherwise skip interpolation
                            if (currentMenuHeight > 0) {
                                fullMenuHeight = currentMenuHeight
                            } else {
                                // Can't interpolate without knowing the full height, skip
                                return@onPageScrolled
                            }
                        }
                    }
                    
                    // Interpolate menu height smoothly during scroll
                    if (fullMenuHeight > 0) {
                        val interpolatedHeight = if (nextIsDialpad) {
                            // Transitioning to dialpad: fade out menu
                            (fullMenuHeight * (1 - positionOffset)).toInt().coerceAtLeast(0)
                        } else {
                            // Transitioning from dialpad: fade in menu
                            (fullMenuHeight * positionOffset).toInt().coerceAtLeast(0)
                        }
                        
                        // Update height smoothly during scroll
                        binding.mainMenu.beVisible()
                        binding.mainMenu.updateLayoutParams<ViewGroup.LayoutParams> {
                            this.height = interpolatedHeight
                        }
                        currentMenuHeight = interpolatedHeight
                    }
                }
            }

            override fun onPageSelected(position: Int) {
                if (config.bottomNavigationBar) {
                    binding.mainTabsHolder.getTabAt(position)?.select()
                }

                // Get current fragment
                val currentFragment = getCurrentFragment()
                
                // Defer heavy operations to avoid blocking the transition
                binding.viewPager.post {
                    // Cache fragments list to avoid multiple getAllFragments() calls
                    val allFragments = getAllFragments()
                    // Batch fragment operations
                    allFragments.forEach { fragment ->
                        fragment?.finishActMode()
                        when (fragment) {
                            is ContactsFragment -> fragment.onFragmentPause()
                            is FavoritesFragment -> fragment.onFragmentPause()
                            is DialpadFragment -> fragment.onFragmentPause()
                        }
                    }
                    
                    // Close search when switching fragments
                    if (binding.mainMenu.isSearchOpen) {
                        binding.mainMenu.closeSearch()
                    }
                    if (isSearchOpen) {
                        val customToolbar = binding.mainMenu.requireCustomToolbar()
                        if (customToolbar.isSearchExpanded) {
                            customToolbar.collapseSearch()
                            isSearchOpen = false
                            getCurrentFragment()?.onSearchClosed()
                            searchQuery = ""
                        }
                    }
                    
                    refreshMenuItems()
                    
                    // Call onFragmentResume on the current fragment
                    when (currentFragment) {
                        is ContactsFragment -> currentFragment.onFragmentResume()
                        is FavoritesFragment -> currentFragment.onFragmentResume()
                        is DialpadFragment -> currentFragment.onFragmentResume()
                    }
                    
                    // Refresh only the current fragment when switching tabs
                    refreshFragments()
                    
                    if (currentFragment == getRecentsFragment()) {
                        clearMissedCalls()
                    }
                }
                
                previousPosition = position
                previousFragment = currentFragment
            }
        })

        // selecting the proper tab sometimes glitches, add an extra selector to make sure we have it right
        if (config.bottomNavigationBar) {
            binding.mainTabsHolder.onGlobalLayout {
                mainHandler.postDelayed({
                    var wantedTab = getDefaultTab()

                    // open the Recents tab if we got here by clicking a missed call notification
                    if (intent.action == Intent.ACTION_VIEW && config.showTabs and TAB_CALL_HISTORY > 0) {
                        wantedTab = binding.mainTabsHolder.tabCount - 2
                    }

                    binding.mainTabsHolder.getTabAt(wantedTab)?.select()
                    refreshMenuItems()
                }, 100L)
            }
        }

        binding.mainDialpadButton.setOnClickListener {
            launchDialpad()
        }

        binding.viewPager.onGlobalLayout {
            refreshMenuItems()
            if (config.bottomNavigationBar && config.changeColourTopBar) scrollChange()
        }

        if (config.openDialPadAtLaunch && !launchedDialer) {
            launchDialpad()
            launchedDialer = true
        }
    }

    private fun scrollChange() {
        val myRecyclerView = getCurrentFragment()?.myRecyclerView()
        scrollingView = myRecyclerView

        val scrollingViewOffset = scrollingView?.computeVerticalScrollOffset() ?: 0
        currentOldScrollY = scrollingViewOffset

        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        val statusBarColor = if (config.changeColourTopBar) getRequiredStatusBarColor(useSurfaceColor) else backgroundColor

        binding.mainMenu.updateColors(statusBarColor, scrollingViewOffset)
        binding.mainMenu.requireCustomToolbar().updateSearchColors()
        setupSearchMenuScrollListener(
            scrollingView = myRecyclerView,
            searchMenu = binding.mainMenu,
            surfaceColor = useSurfaceColor
        )
    }

    private fun setupTabs() {
        // bottom tab bar
        binding.viewPager.adapter = null
        binding.mainTabsHolder.removeAllTabs()
        tabsList.forEachIndexed { index, value ->
            if (config.showTabs and value != 0) {
                val tab = binding.mainTabsHolder.newTab()
                tab.setIcon(getTabIconRes(index))
                tab.setText(getTabLabel(index))
                binding.mainTabsHolder.addTab(tab)
            }
        }

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
            },
            tabSelectedAction = {
                if (config.closeSearch) {
                    binding.mainMenu.closeSearch()
                } else {
                    //On tab switch, the search string is not deleted
                    //It should not start on the first startup
                    if (binding.mainMenu.isSearchOpen) getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }

                binding.viewPager.currentItem = it.position

//                val lastPosition = binding.mainTabsHolder.tabCount - 1
//                if (it.position == lastPosition && config.showTabs and TAB_CALL_HISTORY > 0) {
//                    clearMissedCalls()
//                }

                if (config.openSearch) {
                    if (getCurrentFragment() is ContactsFragment) {
                        binding.mainMenu.requestFocusAndShowKeyboard()
                    }
                }
            }
        )

        binding.mainTabsHolder.beGoneIf(binding.mainTabsHolder.tabCount == 1)
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
        storedShowPhoneNumbers = config.showPhoneNumbers
    }

    private fun getTabLabel(position: Int): String {
        val config = tabConfigs.getOrNull(position) ?: tabConfigs.last()
        return resources.getString(config.labelRes)
    }

    private fun getTabIconRes(position: Int): Int {
        val config = tabConfigs.getOrNull(position) ?: tabConfigs.last()
        return config.iconRes
    }

    private fun refreshItems(openLastTab: Boolean = false) {
        if (isDestroyed || isFinishing) {
            return
        }

        binding.apply {
            if (viewPager.adapter == null) {
                clearFragmentCache() // Clear cache when adapter is recreated
                viewPager.adapter = ViewPagerAdapter(this@MainActivity)
                viewPager.currentItem = if (openLastTab) config.lastUsedViewPagerPage else getDefaultTab()
                viewPager.onGlobalLayout {
                    refreshFragments()
                    // Update menu visibility based on initial fragment
                    val currentFragment = getCurrentFragment()
                    setMainMenuHeight(if (currentFragment is DialpadFragment) 0 else null, animated = false)
                    // Ensure onFragmentResume is called for the initial fragment
                    // This is needed because onPageSelected is not called for the initial page
                    when (currentFragment) {
                        is ContactsFragment -> currentFragment.onFragmentResume()
                        is FavoritesFragment -> currentFragment.onFragmentResume()
                        is DialpadFragment -> currentFragment.onFragmentResume()
                    }
                }
            } else {
                refreshFragments()
            }
        }
    }

    private fun launchDialpad() {
        startActivity(Intent(applicationContext, DialpadActivity::class.java))
    }

    fun refreshFragments() {
        // Only refresh the currently visible fragment instead of all fragments
        when (getCurrentFragment()) {
            is ContactsFragment -> getContactsFragment()?.refreshItems()
            is FavoritesFragment -> getFavoritesFragment()?.refreshItems()
            is RecentsFragment -> getRecentsFragment()?.refreshItems()
            is DialpadFragment -> getDialpadFragment()?.refreshItems(needUpdate = true)
        }
    }

    fun refreshAllFragments() {
        // Refresh all fragments - use this only when needed (e.g., after settings change)
        getContactsFragment()?.refreshItems()
        getFavoritesFragment()?.refreshItems()
        getRecentsFragment()?.refreshItems()
    }

    fun refreshRecentsFragments() {
        // Refresh fragments that show recents (RecentsFragment and DialpadFragment)
        // This is called when contacts are deleted to update contact names in recents
        getRecentsFragment()?.refreshItems(needUpdate = true)
        getDialpadFragment()?.refreshItems(needUpdate = true)
    }

    private fun getAllFragments(): List<MyViewPagerFragment<*>?> {
        val showTabs = config.showTabs
        val fragments = mutableListOf<MyViewPagerFragment<*>?>()

        if (showTabs and TAB_FAVORITES > 0) {
            fragments.add(getFavoritesFragment())
        }

        if (showTabs and TAB_CALL_HISTORY > 0) {
            fragments.add(getRecentsFragment())
        }

        if (showTabs and TAB_CONTACTS > 0) {
            fragments.add(getContactsFragment())
        }

        if (showTabs and TAB_DIALPAD > 0) {
            fragments.add(getDialpadFragment())
        }

        return fragments
    }

    private fun getCurrentFragment(): MyViewPagerFragment<*>? = getAllFragments().getOrNull(binding.viewPager.currentItem)

    private fun getContactsFragment(): ContactsFragment? {
        if (cachedContactsFragment == null) {
            cachedContactsFragment = findViewById(R.id.contacts_fragment)
        }
        return cachedContactsFragment
    }

    private fun getFavoritesFragment(): FavoritesFragment? {
        if (cachedFavoritesFragment == null) {
            cachedFavoritesFragment = findViewById(R.id.favorites_fragment)
        }
        return cachedFavoritesFragment
    }

    private fun getRecentsFragment(): RecentsFragment? {
        if (cachedRecentsFragment == null) {
            cachedRecentsFragment = findViewById(R.id.recents_fragment)
        }
        return cachedRecentsFragment
    }

    private fun getDialpadFragment(): DialpadFragment? {
        if (cachedDialpadFragment == null) {
            cachedDialpadFragment = findViewById(R.id.dialpad_fragment)
        }
        return cachedDialpadFragment
    }
    
    private fun clearFragmentCache() {
        cachedContactsFragment = null
        cachedFavoritesFragment = null
        cachedRecentsFragment = null
        cachedDialpadFragment = null
    }

    private fun getDefaultTab(): Int {
        val showTabsMask = config.showTabs
        val mainTabsHolder = binding.mainTabsHolder
        return when (config.defaultTab) {
            TAB_LAST_USED -> if (config.lastUsedViewPagerPage < mainTabsHolder.tabCount) config.lastUsedViewPagerPage else 0
            TAB_FAVORITES -> 0
            TAB_CALL_HISTORY -> {
                var index = 0
                if (showTabsMask and TAB_FAVORITES > 0) index++
                index
            }
            TAB_DIALPAD -> {
                var index = 0
                if (showTabsMask and TAB_FAVORITES > 0) index++
                if (showTabsMask and TAB_CALL_HISTORY > 0) index++
                if (showTabsMask and TAB_CONTACTS > 0) index++
                index
            }
            else -> {
                if (showTabsMask and TAB_CONTACTS > 0) {
                    if (showTabsMask and TAB_FAVORITES > 0) {
                        if (showTabsMask and TAB_CALL_HISTORY > 0) {
                            2
                        } else {
                            1
                        }
                    } else {
                        if (showTabsMask and TAB_CALL_HISTORY > 0) {
                            1
                        } else {
                            0
                        }
                    }
                } else {
                    0
                }
            }
        }
    }

    private fun launchSettings() {
        binding.mainMenu.closeSearch()
        closeSearch()
        hideKeyboard()
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun showSortingDialog(showCustomSorting: Boolean) {
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        ChangeSortingDialog(this, showCustomSorting, blurTarget) {
            refreshFragmentsWithSearchQuery(getFavoritesFragment(), getContactsFragment())
        }
    }

    private fun showFilterDialog() {
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        FilterContactSourcesDialog(this, blurTarget) {
            config.needUpdateRecents = true
            refreshFragmentsWithSearchQuery(
                getFavoritesFragment(),
                getContactsFragment(),
                getRecentsFragment()
            )
        }
    }
    
    private fun refreshFragmentsWithSearchQuery(vararg fragments: MyViewPagerFragment<*>?) {
        fragments.forEach { fragment ->
            (fragment as? RefreshItemsListener)?.refreshItems(callback = {
                applySearchQueryToCurrentFragment()
            })
        }
    }
    
    private fun applySearchQueryToCurrentFragment() {
        val currentFragment = getCurrentFragment()
        when {
            isSearchOpen -> currentFragment?.onSearchQueryChanged(searchQuery)
            binding.mainMenu.isSearchOpen -> currentFragment?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
        }
    }


    fun cacheFavorites(contacts: List<Contact>) {
        try {
            cachedFavorites.clear()
            cachedFavorites.addAll(contacts)
        } catch (_: Exception) {
        }
        createContactShortcuts()
    }

    private fun setupSecondaryLanguage() {
        if (!DialpadT9.Initialized) {
            val reader = InputStreamReader(resources.openRawResource(R.raw.t9languages))
            DialpadT9.readFromJson(reader.readText())
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshCallLog(event: Events.RefreshCallLog) {
        config.needUpdateRecents = true
        getRecentsFragment()?.refreshItems(needUpdate = true)
    }

    private fun closeSearch() {
        if (isSearchOpen) {
            val allFragments = getAllFragments()
            allFragments.forEach {
                it?.onSearchQueryChanged("")
            }
            val customToolbar = binding.mainMenu.requireCustomToolbar()
            if (customToolbar.isSearchExpanded) {
                customToolbar.collapseSearch()
                isSearchOpen = false
                getCurrentFragment()?.onSearchClosed()
                searchQuery = ""
            }
        }
    }

    private fun checkWhatsNewDialog() {
        whatsNewList().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }

    private val callCallback = object : CallManagerListener {
        override fun onStateChanged() {
            updateState()
        }

        override fun onAudioStateChanged(audioState: AudioRoute) {
        }

        override fun onPrimaryCallChanged(call: Call) {
            updateState()
        }
    }

    private fun updateState() {
        val phoneState = CallManager.getPhoneState()
        when (phoneState) {
            is SingleCall -> {
                RxAnimation.together(
                    binding.mainCallButton.scale(1.1f),
                    binding.mainCallButton.fadeIn(duration = 260),
                ).andThen(
                    binding.mainCallButton.scale(1f)
                ).subscribe()
//                val state = phoneState.call.getStateCompat()
//                if (state == Call.STATE_RINGING) {
//                }
            }

            is TwoCalls -> { }

            else -> {
                RxAnimation.together(
                    binding.mainCallButton.scale(0.6f),
                    binding.mainCallButton.fadeOut(duration = 360),
                ).subscribe()
            }
        }
    }
}
