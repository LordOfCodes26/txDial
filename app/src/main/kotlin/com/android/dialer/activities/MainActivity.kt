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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
    private var mSearchMenuItem: MenuItem? = null
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
    private var mSearchView: SearchView? = null
    
    // Cached fragment references to avoid repeated findViewById calls
    private var cachedContactsFragment: ContactsFragment? = null
    private var cachedFavoritesFragment: FavoritesFragment? = null
    private var cachedRecentsFragment: RecentsFragment? = null
    private var cachedDialpadFragment: DialpadFragment? = null

    // Two-finger swipe detection for secure box
    private lateinit var twoFingerSlideGestureDetector: TwoFingerSlideGestureDetector
    private val mainHandler = Handler(Looper.getMainLooper())

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
                    unlockSecureBoxWithCipher(cipherNumber = 1)
                }
            }
        )
    }
    
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Handle 2 finger gestures globally
        if (ev.pointerCount == 2) {
            twoFingerSlideGestureDetector.onTouchEvent(ev)
            return true
        }

        // One finger gestures pass to default handling
        return super.dispatchTouchEvent(ev)
    }
    
    private fun unlockSecureBoxWithCipher(cipherNumber: Int) {
        // Directly access secure box cipher number 1 without unlock check
        val secureBoxHelper = SecureBoxHelper(this)
        val (calls, contacts) = secureBoxHelper.getSecureBoxByCipherNumber(cipherNumber)
        
        // Navigate to RecentsFragment and show secure box contents filtered by cipher number
        showSecureBoxInRecents(calls, contacts, cipherNumber)
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
            setMainMenuHeight(0)
            EventBus.getDefault().post(Events.RefreshDialpadSettings)
        } else {
            setMainMenuHeight(null)
        }

        // Call onFragmentResume on fragments to register contact observers
        getContactsFragment()?.onFragmentResume()
        getFavoritesFragment()?.onFragmentResume()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
        
        // Call onFragmentPause on fragments to unregister contact observers
        getContactsFragment()?.onFragmentPause()
        getFavoritesFragment()?.onFragmentPause()
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

    private fun setMainMenuHeight(height: Int?) {
        binding.mainMenu.apply {
            if (height != null) {
                // Set specific height (e.g., 0 to hide while keeping it in layout)
                beVisible()
                updateLayoutParams<ViewGroup.LayoutParams> {
                    this.height = height
                }
            } else {
                // Reset to wrap_content (default behavior)
                updateLayoutParams<ViewGroup.LayoutParams> {
                    this.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                beVisible()
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
        return when {
            binding.mainMenu.isSearchOpen -> {
                binding.mainMenu.closeSearch()
                true
            }

            isSearchOpen && mSearchMenuItem != null -> {
                mSearchMenuItem!!.collapseActionView()
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
    }

    private fun refreshMenuItems() {
        val currentFragment = getCurrentFragment()
        val getRecentsFragment = getRecentsFragment()
        val getFavoritesFragment = getFavoritesFragment()
        val dialpadFragment = getDialpadFragment()
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.search).isVisible = /*!config.bottomNavigationBar*/ true // always show the search menu icon
            findItem(R.id.clear_call_history).isVisible = currentFragment == getRecentsFragment
            findItem(R.id.sort).isVisible = currentFragment != getRecentsFragment && currentFragment != dialpadFragment
            findItem(R.id.filter).isVisible = currentFragment != getRecentsFragment && currentFragment != dialpadFragment
            findItem(R.id.create_new_contact).isVisible = currentFragment == getContactsFragment()
            findItem(R.id.change_view_type).isVisible = currentFragment == getFavoritesFragment
            findItem(R.id.column_count).isVisible = currentFragment == getFavoritesFragment && config.viewType == VIEW_TYPE_GRID
            findItem(R.id.show_blocked_numbers).isVisible = currentFragment == getRecentsFragment
            findItem(R.id.show_blocked_numbers).title =
                if (config.showBlockedNumbers) getString(R.string.hide_blocked_numbers) else getString(R.string.show_blocked_numbers)
            findItem(R.id.select).isVisible = currentFragment != null && currentFragment != dialpadFragment
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
            requireToolbar().inflateMenu(R.menu.menu)
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
            } else*/ setupSearch(requireToolbar().menu)

            requireToolbar().setOnMenuItemClickListener { menuItem ->
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
                    R.id.filter -> showFilterDialog()
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

    private fun setupSearch(menu: Menu) {
        updateMenuItemColors(menu)
        val searchManager = getSystemService(SEARCH_SERVICE) as SearchManager
        mSearchMenuItem = menu.findItem(R.id.search)
        mSearchView = (mSearchMenuItem!!.actionView as SearchView).apply {
            val textColor = getProperTextColor()
            findViewById<TextView>(androidx.appcompat.R.id.search_src_text).apply {
                setTextColor(textColor)
                setHintTextColor(textColor)
                // Reduce left padding to a small value
                val smallPadding = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.small_margin)
                setPadding(smallPadding, paddingTop, paddingRight, paddingBottom)
            }
            findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn).apply {
                setImageResource(com.goodwy.commons.R.drawable.ic_clear_round)
                setColorFilter(textColor)
            }
            findViewById<View>(androidx.appcompat.R.id.search_plate)?.apply { // search underline
                background.setColorFilter(Color.TRANSPARENT, PorterDuff.Mode.MULTIPLY)
                // Reduce left padding on the search plate to a small value
                val smallPadding = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.small_margin)
                setPadding(smallPadding, paddingTop, paddingRight, paddingBottom)
            }
            setIconifiedByDefault(false)
            findViewById<ImageView>(androidx.appcompat.R.id.search_mag_icon).apply {
                setColorFilter(textColor)
            }

            setSearchableInfo(searchManager.getSearchableInfo(componentName))
            isSubmitButtonEnabled = false
            queryHint = getString(R.string.search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String) = false

                override fun onQueryTextChange(newText: String): Boolean {
                    if (isSearchOpen) {
                        searchQuery = newText
                        getCurrentFragment()?.onSearchQueryChanged(newText)
                    }
                    return true
                }
            })
        }

        @Suppress("DEPRECATION")
        MenuItemCompat.setOnActionExpandListener(mSearchMenuItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                isSearchOpen = true
                // Keep dialpad button visible when search is opened
                
                // Animate search bar appearance with smooth translation (slide in from right)
                mSearchView?.let { searchView ->
                    searchView.post {
                        // Get the parent toolbar width for smooth slide-in
                        val toolbar = binding.mainMenu.requireToolbar()
                        val slideDistance = toolbar.width.toFloat()
                        
                        // Start from right side
                        searchView.translationX = slideDistance
                        searchView.alpha = 0f
                        
                        // Animate to center with smooth deceleration
                        searchView.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(350)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                            .start()
                    }
                }
                
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
                if (isSearchOpen) {
                    getCurrentFragment()?.onSearchClosed()
                }

                isSearchOpen = false
                
                // Animate search bar disappearance with smooth translation (slide out to right)
                mSearchView?.let { searchView ->
                    val toolbar = binding.mainMenu.requireToolbar()
                    val slideDistance = toolbar.width.toFloat()
                    
                    searchView.animate()
                        .translationX(slideDistance)
                        .alpha(0f)
                        .setDuration(300)
                        .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                        .withEndAction {
                            searchView.translationX = 0f
                            searchView.alpha = 1f
                            binding.mainDialpadButton.beGone() // Always hide the dialpad button
                        }
                        .start()
                } ?: run {
                    binding.mainDialpadButton.beGone() // Always hide the dialpad button
                }
                
                return true
            }
        })
    }

    private fun showBlockedNumbers() {
        config.showBlockedNumbers = !config.showBlockedNumbers
        binding.mainMenu.requireToolbar().menu.findItem(R.id.show_blocked_numbers).title =
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
                    getRecentsFragment()?.refreshItems(invalidate = true)
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
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                if (config.bottomNavigationBar && config.changeColourTopBar) scrollChange()
            }

            override fun onPageSelected(position: Int) {
                if (config.bottomNavigationBar) {
                    binding.mainTabsHolder.getTabAt(position)?.select()
                }

                // Cache fragments list to avoid multiple getAllFragments() calls
                val allFragments = getAllFragments()
                // Batch fragment operations
                allFragments.forEach { fragment ->
                    fragment?.finishActMode()
                    when (fragment) {
                        is ContactsFragment -> fragment.onFragmentPause()
                        is FavoritesFragment -> fragment.onFragmentPause()
                    }
                }
                
                // Hide main menu when dialpad fragment is shown
                val currentFragment = getCurrentFragment()
                if (currentFragment is DialpadFragment) {
                    // Close search if open
                    if (binding.mainMenu.isSearchOpen) {
                        binding.mainMenu.closeSearch()
                    }
                    if (isSearchOpen && mSearchMenuItem != null) {
                        mSearchMenuItem!!.collapseActionView()
                        isSearchOpen = false
                    }
                    setMainMenuHeight(0)
                } else {
                    setMainMenuHeight(null)
                }
                
                refreshMenuItems()
                
                // Call onFragmentResume on the current fragment
                when (currentFragment) {
                    is ContactsFragment -> currentFragment.onFragmentResume()
                    is FavoritesFragment -> currentFragment.onFragmentResume()
                }
                
                // Refresh only the current fragment when switching tabs
                refreshFragments()
                
                if (currentFragment == getRecentsFragment()) {
                    clearMissedCalls()
                }
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
                    setMainMenuHeight(if (currentFragment is DialpadFragment) 0 else null)
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
            // DialpadFragment doesn't have refreshItems() method
        }
    }

    fun refreshAllFragments() {
        // Refresh all fragments - use this only when needed (e.g., after settings change)
        getContactsFragment()?.refreshItems()
        getFavoritesFragment()?.refreshItems()
        getRecentsFragment()?.refreshItems()
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
            mSearchMenuItem?.collapseActionView()
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
