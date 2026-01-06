package com.android.dialer.activities

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.os.VibrationEffect
import android.telecom.Call
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.isVisible
import com.goodwy.commons.dialogs.ConfirmationAdvancedDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.SimpleListItem
import com.android.dialer.R
import com.android.dialer.databinding.ActivityCallBinding
import com.android.dialer.dialogs.ChangeTextDialog
import com.android.dialer.extensions.*
import com.android.dialer.helpers.*
import com.android.dialer.helpers.RECORDING_RULE_NONE
import com.android.dialer.models.*
import com.mikhaellopez.rxanimation.*
import com.mikhaellopez.rxanimation.fadeIn
import com.mikhaellopez.rxanimation.fadeOut
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.get
import androidx.core.view.size
import com.android.dialer.helpers.CallManager.Companion.isSpeakerOn
import com.android.dialer.databinding.DialogCustomBackgroundTuningBinding
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import kotlin.math.roundToInt


class CallActivity : SimpleActivity() {
    companion object {
        private const val CUSTOM_BACKGROUND_ALPHA_DEFAULT = 80  // 0-255, lower = more transparent
        private const val CUSTOM_BACKGROUND_BLUR_SCALE = 0.3f
        private const val CUSTOM_BACKGROUND_BLUR_RADIUS_DEFAULT = 20f

        fun getStartIntent(context: Context, needSelectSIM: Boolean = false): Intent {
            val openAppIntent = Intent(context, CallActivity::class.java)
            openAppIntent.putExtra(NEED_SELECT_SIM, needSelectSIM)
            //Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT --removed it, it can cause a full screen ringing instead of notifications
            openAppIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            return openAppIntent
        }
    }

    private val binding by viewBinding(ActivityCallBinding::inflate)
    
    // Cache blur target to avoid repeated findViewById calls
    private val blurTarget: BlurTarget by lazy {
        findViewById<BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
    }

    private var isMicrophoneOff = false
    private var isMicrophoneInitialized = false
    private var isCallEnded = false
    private var callContact: CallContact? = null
    
    // Cached values for performance
    private val isSmallScreen by lazy {
        resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK == Configuration.SCREENLAYOUT_SIZE_SMALL
    }
    
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var screenOnWakeLock: PowerManager.WakeLock? = null
    private var callDuration = 0
    private val callContactAvatarHelper by lazy { CallContactAvatarHelper(this) }
    private val callDurationHandler = Handler(Looper.getMainLooper())
    private var dragDownX = 0f
    private var stopAnimation = false
    private var dialpadHeight = 0f
    private var needSelectSIM = false //true - if the call is called from a third-party application not via ACTION_CALL, for example, this is how MIUI applications do it.
    private var audioRoutePopupMenu: PopupMenu? = null
    private var videoBackgroundView: android.widget.VideoView? = null
    private var cachedCustomBackgroundBitmap: Bitmap? = null
    private var cachedWallpaperDrawable: Drawable? = null
    
    // Shake to answer
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var shakeDetector: ShakeDetector? = null
    
    // Cached arrays for performance
    private val whiteTextViews by lazy {
        binding.run {
            arrayOf(
                callerNameLabel, callerDescription, callerNumber, callerNotes, callStatusLabel, callDeclineLabel, callAcceptLabel,
                dialpadInclude.dialpad1, dialpadInclude.dialpad2, dialpadInclude.dialpad3, dialpadInclude.dialpad4,
                dialpadInclude.dialpad5, dialpadInclude.dialpad6, dialpadInclude.dialpad7, dialpadInclude.dialpad8,
                dialpadInclude.dialpad9, dialpadInclude.dialpad0, dialpadInclude.dialpadPlus, dialpadInput,
                dialpadInclude.dialpad2Letters, dialpadInclude.dialpad3Letters, dialpadInclude.dialpad4Letters,
                dialpadInclude.dialpad5Letters, dialpadInclude.dialpad6Letters, dialpadInclude.dialpad7Letters,
                dialpadInclude.dialpad8Letters, dialpadInclude.dialpad9Letters,
                onHoldCallerName, onHoldLabel, callMessageLabel, callRemindLabel,
                callToggleMicrophoneLabel, callDialpadLabel, callToggleSpeakerLabel, callAddLabel,
                callSwapLabel, callMergeLabel, callToggleLabel, callAddContactLabel,
                callRedialLabel, callToggleRecordingLabel,
                dialpadClose, callEndLabel, callAcceptAndDecline
            )
        }
    }
    
    private val whiteImageViews by lazy {
        binding.run {
            arrayOf(
                callToggleMicrophone, callToggleSpeaker, callDialpad, callSimImage, callDetails,
                callToggleHold, callAddContact, callAdd, callSwap, callMerge, callInfo, addCallerNote, imageView,
                dialpadInclude.dialpadAsterisk, dialpadInclude.dialpadHashtag, callRedial
            ).filterNotNull()
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        addLockScreenFlags()
        super.onCreate(savedInstanceState)
        updateSystemBarsAppearance = false

        setContentView(binding.root)

        if (CallManager.getPhoneState() == NoCall) {
            finish()
            return
        }

        setupEdgeToEdge(
            padTopSystem = listOf(binding.callHolder),
            padBottomSystem = listOf(binding.callHolder),
            moveTopSystem = listOf(binding.callerAvatar, binding.onHoldStatusHolder)
        )

        needSelectSIM = intent.getBooleanExtra(NEED_SELECT_SIM, false)
        if (needSelectSIM) initOutgoingCall(CallManager.getPrimaryCall()!!.details.handle)

        initButtons()

        try {
            audioManager.mode = AudioManager.MODE_IN_CALL
        } catch (_: Exception) {
        }

        CallManager.addListener(callCallback)
        updateTextColors(binding.callHolder)

        val configBackgroundCallScreen = config.backgroundCallScreen
        if (configBackgroundCallScreen != THEME_BACKGROUND ) {
            window.setSystemBarsAppearance(Color.BLACK)

            if (configBackgroundCallScreen == BLACK_BACKGROUND) {
                binding.callHolder.setBackgroundColor(Color.BLACK)
            } else if (configBackgroundCallScreen == CUSTOM_BACKGROUND) {
                applyCustomBackground()
            } else if (configBackgroundCallScreen == BLUR_AVATAR || configBackgroundCallScreen == AVATAR) {
                // These are handled in updateCallContactInfo
            } else if (configBackgroundCallScreen == VIDEO_BACKGROUND) {
                applyVideoBackground()
            } else if (configBackgroundCallScreen == TRANSPARENT_BACKGROUND) {
                applyTransparentBackground()
            }
            
            // Add long-press on background to open tuning dialog for all background types except video
            if (configBackgroundCallScreen != VIDEO_BACKGROUND && configBackgroundCallScreen != THEME_BACKGROUND) {
                binding.callHolder.setOnLongClickListener {
                    if (config.backgroundCallScreen != VIDEO_BACKGROUND && config.backgroundCallScreen != THEME_BACKGROUND) {
                        promptCustomBackgroundTuning()
                        true
                    } else {
                        false
                    }
                }
            }

            binding.apply {
                // Use cached arrays for better performance
                whiteTextViews.forEach { it.setTextColor(Color.WHITE) }
                whiteImageViews.forEach { it.applyColorFilter(Color.WHITE) }
                callSimId.setTextColor(Color.WHITE.getContrastColor())
            }
        } else {
            //THEME_BACKGROUND
            val backgroundColor = getProperBackgroundColor()
            binding.callHolder.setBackgroundColor(backgroundColor)
            window.setSystemBarsAppearance(backgroundColor)

            val properTextColor = getProperTextColor()
            binding.apply {
                arrayOf(
                    callToggleMicrophone, callToggleSpeaker, callDialpad, /*dialpadClose,*/ callSimImage, callDetails,
                    callToggleHold, callAddContact, callAdd, callSwap, callMerge, callInfo, addCallerNote, imageView,
                    dialpadInclude.dialpadAsterisk, dialpadInclude.dialpadHashtag, callMessage, callRemind, callRedial
                ).filterNotNull().forEach {
                    it.applyColorFilter(properTextColor)
                }

                callSimId.setTextColor(properTextColor.getContrastColor())
                dialpadInput.disableKeyboard()

                dialpadWrapper.onGlobalLayout {
                    dialpadHeight = dialpadWrapper.height.toFloat()
                }
            }
        }
        updateCallContactInfo(CallManager.getPrimaryCall())

        binding.apply {
            arrayOf(
                callToggleMicrophone, callToggleSpeaker, callToggleHold, onHoldStatusHolder,
                callRemind, callMessage,
                callDialpadHolder, callAddContactHolder, callAddHolder, callSwapHolder, callMergeHolder,
                callRedialHolder, callAcceptAndDecline
            ).forEach {
                it.background.applyColorFilter(Color.GRAY)
                it.background.alpha = 60
            }
            arrayOf(
                dialpadInclude.dialpad0Holder, dialpadInclude.dialpad1Holder, dialpadInclude.dialpad2Holder, dialpadInclude.dialpad3Holder,
                dialpadInclude.dialpad4Holder, dialpadInclude.dialpad5Holder, dialpadInclude.dialpad6Holder, dialpadInclude.dialpad7Holder,
                dialpadInclude.dialpad8Holder, dialpadInclude.dialpad9Holder, dialpadInclude.dialpadAsteriskHolder, dialpadInclude.dialpadHashtagHolder
            ).forEach {
                it.foreground.applyColorFilter(Color.GRAY)
                it.foreground.alpha = 60
            }


            if (config.hideDialpadLetters) {
                arrayOf(
                    dialpadInclude.dialpad1Letters,
                    dialpadInclude.dialpad2Letters,
                    dialpadInclude.dialpad3Letters,
                    dialpadInclude.dialpad4Letters,
                    dialpadInclude.dialpad5Letters,
                    dialpadInclude.dialpad6Letters,
                    dialpadInclude.dialpad7Letters,
                    dialpadInclude.dialpad8Letters,
                    dialpadInclude.dialpad9Letters
                ).forEach {
                    it.beGone()
                }
            }
        }

        if (config.callButtonStyle == IOS17 || isSmallScreen) {
            binding.callEndLabel.beVisible()
            binding.callAddContactHolder.beGone()

            val callAddHolderParams = binding.callAddHolder.layoutParams as ConstraintLayout.LayoutParams
            callAddHolderParams.topToTop = binding.callEnd.id
            callAddHolderParams.bottomToBottom = binding.callEnd.id
            callAddHolderParams.topMargin = 0
            binding.callAddHolder.requestLayout()
            val callSwapHolderParams = binding.callSwapHolder.layoutParams as ConstraintLayout.LayoutParams
            callSwapHolderParams.topToTop = binding.callEnd.id
            callSwapHolderParams.bottomToBottom = binding.callEnd.id
            callSwapHolderParams.topMargin = 0
            binding.callSwapHolder.requestLayout()

            val marginStartEnd = resources.getDimension(R.dimen.margin_button_horizontal).toInt()
            val callToggleHoldParams = binding.callToggleHold.layoutParams as ConstraintLayout.LayoutParams
            callToggleHoldParams.topToTop = binding.callEnd.id
            callToggleHoldParams.bottomToBottom = binding.callEnd.id
            callToggleHoldParams.leftToRight = binding.callEnd.id
            callToggleHoldParams.topMargin = 0
            callToggleHoldParams.marginStart = marginStartEnd
            binding.callToggleHold.requestLayout()
            val callMergeHolderParams = binding.callMergeHolder.layoutParams as ConstraintLayout.LayoutParams
            callMergeHolderParams.topToTop = binding.callEnd.id
            callMergeHolderParams.bottomToBottom = binding.callEnd.id
            callMergeHolderParams.leftToRight = binding.callEnd.id
            callMergeHolderParams.topMargin = 0
            callMergeHolderParams.marginStart = marginStartEnd
            binding.callMergeHolder.requestLayout()

            val marginBottom =
                if (isSmallScreen) resources.getDimension(R.dimen.call_button_row_margin_small).toInt()
                else resources.getDimension(R.dimen.call_button_row_margin).toInt()
            val callDialpadHolderParams = binding.callDialpadHolder.layoutParams as ConstraintLayout.LayoutParams
            callDialpadHolderParams.topToTop = -1
            callDialpadHolderParams.bottomToBottom = -1
            callDialpadHolderParams.bottomToTop = binding.callEnd.id
            callDialpadHolderParams.bottomMargin = marginBottom
            binding.callDialpadHolder.requestLayout()
        }
        if (isSmallScreen) {
            binding.apply {
                arrayOf(
                    callDeclineLabel, callAcceptLabel, callMessageLabel, callRemindLabel,
                    callToggleMicrophoneLabel, callDialpadLabel, callToggleSpeakerLabel, callAddLabel,
                    callSwapLabel, callMergeLabel, callToggleLabel, callAddContactLabel, callEndLabel
                ).forEach {
                    it.beGone()
                }
            }
        }

        if (config.flashForAlerts) {
            val phoneState = CallManager.getPhoneState()
            if (phoneState is SingleCall) {
                val call = phoneState.call
                val isDeviceLocked = !powerManager.isInteractive || keyguardManager.isDeviceLocked
                if (!call.isOutgoing() && isDeviceLocked) MyCameraImpl.newInstance(this).toggleSOS()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initOutgoingCall(callNumber: Uri) {
        try {
            getHandleToUse(intent, callNumber.toString()) { handle ->
                if (handle != null) {
                    CallManager.getPrimaryCall()?.phoneAccountSelected(handle, false)
                }
            }
        } catch (e: Exception) {
            showErrorToast(e)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        updateState()
    }

    override fun onResume() {
        super.onResume()
        updateState()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingSuperCall", "Wakelock")
    override fun onDestroy() {
        super.onDestroy()
        CallManager.removeListener(callCallback)
        disableProximitySensor()
        disableShakeDetection()
        videoBackgroundView?.stopPlayback()
        
        // Clear cached bitmaps and drawables to free memory
        cachedCustomBackgroundBitmap?.recycle()
        cachedCustomBackgroundBitmap = null
        cachedWallpaperDrawable = null

        if (isOreoMr1Plus()) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }

        window.clearFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        if (screenOnWakeLock?.isHeld == true) {
            screenOnWakeLock!!.release()
        }
        if (config.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }

    override fun onBackPressedCompat(): Boolean {
        if (binding.dialpadWrapper.isVisible()) {
            hideDialpad()
            return true
        }

        if (config.backPressedEndCall) {
            endCall()
            return true
        }

        // Allow minimizing active call - user can return via notification
        return false
    }

    private fun initButtons() = binding.apply {
        when (config.answerStyle) {
            ANSWER_SLIDER, ANSWER_SLIDER_OUTLINE -> {
                arrayOf(
                    callDecline, callDeclineLabel,
                    callAccept, callAcceptLabel,
                    callDraggableVertical, callUpArrow, callDownArrow
                ).forEach {
                    it.beGone()
                }
                handleSwipe()
            }
            ANSWER_SLIDER_VERTICAL -> {
                arrayOf(
                    callDecline, callDeclineLabel,
                    callAccept, callAcceptLabel,
                    callDraggable, callDraggableBackground,
                    callLeftArrow, callRightArrow
                ).forEach {
                    it.beGone()
                }
                handleSwipeVertical()
            }
            else -> {
                arrayOf(
                    callDraggable, callDraggableBackground, callDraggableVertical,
                    callLeftArrow, callRightArrow,
                    callUpArrow, callDownArrow
                ).forEach {
                    it.beGone()
                }

                callDecline.setOnClickListener {
                    endCall()
                }

                callAccept.setOnClickListener {
                    acceptCall()
                }
            }
        }

        callToggleMicrophone.setOnClickListener {
            toggleMicrophone()
            maybePerformDialpadHapticFeedback(it)
        }

        callToggleSpeaker.setOnClickListener {
            changeCallAudioRoute()
            maybePerformDialpadHapticFeedback(it)
        }

        callToggleSpeaker.setOnLongClickListener {
//            if (CallManager.getCallAudioRoute() == AudioRoute.BLUETOOTH) {
//                openBluetoothSettings()
            val supportAudioRoutes = CallManager.getSupportedAudioRoutes()
            if (supportAudioRoutes.size > 2) {
                CallManager.toggleSpeakerRoute()
            }
            else toast(callToggleSpeaker.contentDescription.toString())
            maybePerformDialpadHapticFeedback(it)
            true
        }

        callToggleRecording.setOnClickListener {
            toggleRecording()
            maybePerformDialpadHapticFeedback(it)
        }

        callDialpadHolder.setOnClickListener {
            toggleDialpadVisibility()
            maybePerformDialpadHapticFeedback(it)
        }

        dialpadClose.setOnClickListener {
            hideDialpad()
            maybePerformDialpadHapticFeedback(it)
        }

        callAddHolder.setOnClickListener {
            Intent(applicationContext, DialpadActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                startActivity(this)
            }
            maybePerformDialpadHapticFeedback(it)
        }

        callSwapHolder.setOnClickListener {
            CallManager.swap()
            maybePerformDialpadHapticFeedback(it)
        }

        callMergeHolder.setOnClickListener {
            CallManager.merge()
            maybePerformDialpadHapticFeedback(it)
        }

        callInfo.setOnClickListener {
            startActivity(Intent(this@CallActivity, ConferenceActivity::class.java))
            maybePerformDialpadHapticFeedback(it)
        }

        callToggleHold.setOnClickListener {
            toggleHold()
            maybePerformDialpadHapticFeedback(it)
        }

        callAddContactHolder.setOnClickListener {
            addContact()
            maybePerformDialpadHapticFeedback(it)
        }

        callRedialHolder.setOnClickListener {
            // If auto redial setting is enabled, toggle the active state
            if (config.autoRedialEnabled) {
                val newState = !CallManager.isAutoRedialActive()
                CallManager.setAutoRedialActive(newState)
                toast(if (newState) R.string.auto_redial_enabled else R.string.auto_redial_disabled)
                updateRedialButtonVisibility()
                maybePerformDialpadHapticFeedback(it)
                return@setOnClickListener
            }
            
            // If auto redial setting is disabled, perform manual redial
            val lastNumber = CallManager.getLastDialedNumber()
            if (lastNumber != null) {
                handlePermission(PERMISSION_CALL_PHONE) {
                    val primaryCall = CallManager.getPrimaryCall()
                    val callState = primaryCall?.getStateCompat()
                    
                    // If there's an active call, disconnect it first before redialing
                    if (callState != null && callState != Call.STATE_DISCONNECTED && callState != Call.STATE_DISCONNECTING) {
                        // Disconnect the current call
                        CallManager.reject()
                        
                        // Wait for the call to disconnect before redialing
                        // Reuse existing handler instead of creating a new one
                        var retryCount = 0
                        val maxRetries = 20 // Wait up to 2 seconds (20 * 100ms)
                        
                        val checkDisconnected = object : Runnable {
                            override fun run() {
                                val currentCall = CallManager.getPrimaryCall()
                                val currentState = currentCall?.getStateCompat()
                                
                                if (currentState == Call.STATE_DISCONNECTED || currentState == null || CallManager.getPhoneState() == NoCall) {
                                    // Call is disconnected, now redial
                                    CallManager.redial(this@CallActivity)
                                } else if (retryCount < maxRetries) {
                                    // Still disconnecting, check again
                                    retryCount++
                                    callDurationHandler.postDelayed(this, 100)
                                } else {
                                    // Timeout, try redialing anyway
                                    CallManager.redial(this@CallActivity)
                                }
                            }
                        }
                        callDurationHandler.postDelayed(checkDisconnected, 300)
                    } else {
                        // No active call, just redial immediately
                        CallManager.redial(this@CallActivity)
                    }
                }
            } else {
                toast(R.string.no_number_to_redial)
            }
            maybePerformDialpadHapticFeedback(it)
        }

        callEnd.setOnClickListener {
            endCall()
        }

        dialpadInclude.apply {
            dialpad0Holder.setOnClickListener { dialpadPressed('0') }
            dialpad1Holder.setOnClickListener { dialpadPressed('1') }
            dialpad2Holder.setOnClickListener { dialpadPressed('2') }
            dialpad3Holder.setOnClickListener { dialpadPressed('3') }
            dialpad4Holder.setOnClickListener { dialpadPressed('4') }
            dialpad5Holder.setOnClickListener { dialpadPressed('5') }
            dialpad6Holder.setOnClickListener { dialpadPressed('6') }
            dialpad7Holder.setOnClickListener { dialpadPressed('7') }
            dialpad8Holder.setOnClickListener { dialpadPressed('8') }
            dialpad9Holder.setOnClickListener { dialpadPressed('9') }

            dialpad0Holder.setOnLongClickListener { dialpadPressed('+'); true }
            dialpadAsteriskHolder.setOnClickListener { dialpadPressed('*') }
            dialpadHashtagHolder.setOnClickListener { dialpadPressed('#') }
        }

        arrayOf(
            callToggleMicrophone, callDialpadHolder, callToggleHold,
            callAddHolder, callSwapHolder, callMergeHolder, callInfo, addCallerNote, callAddContactHolder
        ).forEach { imageView ->
            imageView.setOnLongClickListener {
                if (!imageView.contentDescription.isNullOrEmpty()) {
                    toast(imageView.contentDescription.toString())
                }
                true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleSwipe() = binding.apply {
        var minDragX = 0f
        var maxDragX = 0f
        var initialDraggableX = 0f
        var initialLeftArrowX = 0f
        var initialRightArrowX = 0f
        var initialLeftArrowScaleX = 0f
        var initialLeftArrowScaleY = 0f
        var initialRightArrowScaleX = 0f
        var initialRightArrowScaleY = 0f
        var leftArrowTranslation = 0f
        var rightArrowTranslation = 0f
        var initialBackgroundWidth = 0

        val isRtl = isRTLLayout
        callAccept.onGlobalLayout {
            minDragX = if (isRtl) callDraggableBackground.left.toFloat() + resources.getDimension(R.dimen.three_dp)
                        else callDraggableBackground.left.toFloat() - callDraggable.width.toFloat()
            maxDragX = if (isRtl) callDraggableBackground.right.toFloat() - 60f
                        else callDraggableBackground.right.toFloat() - callDraggable.width.toFloat() - resources.getDimension(R.dimen.three_dp) - 20f
            initialDraggableX = if (isRtl) callDraggableBackground.right.toFloat() - callDraggable.width.toFloat() else callDraggableBackground.left.toFloat() + resources.getDimension(R.dimen.three_dp)
            initialLeftArrowX = callLeftArrow.x
            initialRightArrowX = callRightArrow.x
            initialLeftArrowScaleX = callLeftArrow.scaleX
            initialLeftArrowScaleY = callLeftArrow.scaleY
            initialRightArrowScaleX = callRightArrow.scaleX
            initialRightArrowScaleY = callRightArrow.scaleY
            leftArrowTranslation = if (isRtl) 50f else -50f //-callDraggableBackground.x
            rightArrowTranslation = if (isRtl) -50f else 50f //callDraggableBackground.x
            initialBackgroundWidth = callDraggableBackground.width

            callLeftArrow.applyColorFilter(getColor(R.color.red_call))
            callRightArrow.applyColorFilter(getColor(R.color.green_call))

            startArrowAnimation(callLeftArrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
            startArrowAnimation(callRightArrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)
        }

        val configBackgroundCallScreen = config.backgroundCallScreen
        if (config.answerStyle == ANSWER_SLIDER_OUTLINE) {
            callDraggableBackground.background = AppCompatResources.getDrawable(this@CallActivity, R.drawable.call_draggable_background_stroke)
            val colorBg = if (configBackgroundCallScreen == THEME_BACKGROUND) getProperTextColor() else Color.WHITE
            callDraggableBackgroundIcon.background.mutate().setTint(colorBg)
        } else {
            callDraggableBackground.background.alpha = 51 // 20%
        }
        val colorBg =
            if (configBackgroundCallScreen == TRANSPARENT_BACKGROUND
                || configBackgroundCallScreen == BLUR_AVATAR
                || configBackgroundCallScreen == AVATAR
                || configBackgroundCallScreen == BLACK_BACKGROUND
                ) Color.WHITE else getProperTextColor()
        callDraggableBackgroundIcon.drawable.mutate().setTint(getColor(R.color.green_call))
        callDraggableBackgroundIcon.background.mutate().setTint(colorBg)
        callDraggableBackground.background.mutate().setTint(colorBg)

        var lock = false
        callDraggable.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.x
                    //callDraggableBackground.animate().alpha(0f)
                    stopAnimation = true
                    callLeftArrow.animate().alpha(0f)
                    callRightArrow.animate().alpha(0f)
                    lock = false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDownX = 0f
//                    callDraggable.animate().x(initialDraggableX).withEndAction {
//                        callDraggableBackground.animate().alpha(0.2f)
//                    }
                    callDraggable.x(initialDraggableX)
                    callDraggableBackgroundIcon.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_phone_down_vector))
                    callDraggableBackgroundIcon.drawable.mutate().setTint(getColor(R.color.green_call))
                    callLeftArrow.animate().alpha(1f)
                    callRightArrow.animate().alpha(1f)
                    stopAnimation = false
                    startArrowAnimation(callLeftArrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
                    startArrowAnimation(callRightArrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)

                    callDraggableBackground.layoutParams.width = initialBackgroundWidth
                    callDraggableBackground.layoutParams = callDraggableBackground.layoutParams
                }

                MotionEvent.ACTION_MOVE -> {
                    callDraggable.x = min(maxDragX, max(minDragX, event.rawX - dragDownX))
                    callDraggableBackground.layoutParams.width = if (isRtl) (initialBackgroundWidth - (maxDragX + 60 - callDraggable.height.toFloat() - callDraggable.x)).toInt()
                        else (initialBackgroundWidth + (minDragX + resources.getDimension(R.dimen.three_dp) + callDraggable.width - callDraggable.x)).toInt()
                    callDraggableBackground.layoutParams = callDraggableBackground.layoutParams
                    //callerNameLabel.text = callDraggable.x.toString() + "   " + initialBackgroundWidth.toString() + "   " + (minDragX + callDraggable.width - callDraggable.x).toString()
                    when {
                        callDraggable.x >= maxDragX -> {
                            if (!lock) {
                                lock = true
                                if (isRtl) {
                                    endCall()
                                } else {
                                    acceptCall()
                                }
                            }
                        }

                        callDraggable.x <= minDragX + 50f -> {
                            if (!lock) {
                                lock = true
                                if (isRtl) {
                                    acceptCall()
                                } else {
                                    endCall()
                                }
                            }
                        }

                        callDraggable.x > initialDraggableX + 20f -> {
                            lock = false
                            val drawableRes = if (isRtl) {
                                R.drawable.ic_phone_down_red_vector
                            } else {
                                R.drawable.ic_phone_green_vector
                            }
                            callDraggableBackgroundIcon.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, drawableRes))
                        }

                        callDraggable.x < initialDraggableX - 20f -> {
                            lock = false
                            val drawableRes = if (isRtl) {
                                R.drawable.ic_phone_green_vector
                            } else {
                                R.drawable.ic_phone_down_red_vector
                            }
                            callDraggableBackgroundIcon.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, drawableRes))
                        }

                        callDraggable.x <= initialDraggableX + 20f || callDraggable.x >= initialDraggableX - 20f -> {
                            lock = false
                            callDraggableBackgroundIcon.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_phone_down_vector))
                            callDraggableBackgroundIcon.drawable.mutate().setTint(getColor(R.color.green_call))
                        }
                    }
                }
            }
            true
        }
    }

    private fun startArrowAnimation(arrow: ImageView, initialX: Float, initialScaleX: Float, initialScaleY: Float, translation: Float) {
        arrow.apply {
            alpha = 1f
            x = initialX
            scaleX = initialScaleX
            scaleY = initialScaleY
            animate()
                .alpha(0f)
                .translationX(translation)
                .scaleXBy(-0.5f)
                .scaleYBy(-0.5f)
                .setDuration(1000)
                .withEndAction {
                    if (!stopAnimation) {
                        startArrowAnimation(this, initialX, initialScaleX, initialScaleY, translation)
                    }
                }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleSwipeVertical() = binding.apply {
        var minDragY = 0f
        var maxDragY = 0f
        var initialDraggableY = 0f
        var initialDownArrowY = 0f
        var initialUpArrowY = 0f
        var initialDownArrowScaleX = 0f
        var initialDownArrowScaleY = 0f
        var initialUpArrowScaleX = 0f
        var initialUpArrowScaleY = 0f
        var downArrowTranslation = 0f
        var upArrowTranslation = 0f

        callDraggableVertical.onGlobalLayout {
            minDragY = callDraggableVertical.top.toFloat() - callDraggableVertical.height.toFloat()
            maxDragY = callDraggableVertical.bottom.toFloat()
            initialDraggableY = callDraggableVertical.top.toFloat()
            initialDownArrowY = callDownArrow.y
            initialUpArrowY = callUpArrow.y
            initialDownArrowScaleX = callDownArrow.scaleX
            initialDownArrowScaleY = callDownArrow.scaleY
            initialUpArrowScaleX = callUpArrow.scaleX
            initialUpArrowScaleY = callUpArrow.scaleY
            downArrowTranslation = 50f
            upArrowTranslation = -50f

            callDownArrow.applyColorFilter(getColor(R.color.red_call))
            callUpArrow.applyColorFilter(getColor(R.color.green_call))

            startArrowAnimationVertical(callDownArrow, initialDownArrowY, initialDownArrowScaleX, initialDownArrowScaleY, downArrowTranslation)
            startArrowAnimationVertical(callUpArrow, initialUpArrowY, initialUpArrowScaleX, initialUpArrowScaleY, upArrowTranslation)
        }

        val configBackgroundCallScreen = config.backgroundCallScreen
        val colorBg =
            if (configBackgroundCallScreen == TRANSPARENT_BACKGROUND
                || configBackgroundCallScreen == BLUR_AVATAR
                || configBackgroundCallScreen == AVATAR
                || configBackgroundCallScreen == BLACK_BACKGROUND
            ) Color.WHITE else getProperTextColor()
        callDraggableVertical.drawable.mutate().setTint(getColor(R.color.green_call))
        callDraggableVertical.background.mutate().setTint(colorBg)
        //callDraggableVertical.background.alpha = 51 // 20%

        var lock = false
        callDraggableVertical.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
//                    dragDownX = event.y
                    //callDraggableBackground.animate().alpha(0f)
                    stopAnimation = true
                    callDownArrow.animate().alpha(0f)
                    callUpArrow.animate().alpha(0f)
                    lock = false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDownX = 0f
                    callDraggableVertical.animate().y(initialDraggableY)
                    callDraggableVertical.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_phone_down_vector))
                    callDraggableVertical.drawable.mutate().setTint(getColor(R.color.green_call))
                    callDownArrow.animate().alpha(1f)
                    callUpArrow.animate().alpha(1f)
                    stopAnimation = false
                    startArrowAnimationVertical(callDownArrow, initialDownArrowY, initialDownArrowScaleX, initialDownArrowScaleY, downArrowTranslation)
                    startArrowAnimationVertical(callUpArrow, initialUpArrowY, initialUpArrowScaleX, initialUpArrowScaleY, upArrowTranslation)
                }

                MotionEvent.ACTION_MOVE -> {
                    callDraggableVertical.y = min(maxDragY, max(minDragY, event.rawY - dragDownX - statusBarHeight))
                    //callerNameLabel.text = callDraggableVertical.y.toString() + "   " + statusBarHeight.toString() + "   " + callDraggableVertical.top.toFloat().toString()
                    when {
                        callDraggableVertical.y >= maxDragY -> {
                            if (!lock) {
                                lock = true
                                endCall()
                            }
                        }
                        callDraggableVertical.y <= minDragY -> {
                            if (!lock) {
                                lock = true
                                acceptCall()
                            }
                        }
                        callDraggableVertical.y > initialDraggableY + 20f -> {
                            lock = false
                            callDraggableVertical.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_phone_down_red_vector))
                        }
                        callDraggableVertical.y < initialDraggableY - 20f -> {
                            lock = false
                            callDraggableVertical.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_phone_green_vector))
                        }
                        callDraggableVertical.y <= initialDraggableY + 20f || callDraggableVertical.y >= initialDraggableY - 20f -> {
                            lock = false
                            callDraggableVertical.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_phone_down_vector))
                            callDraggableVertical.drawable.mutate().setTint(getColor(R.color.green_call))
                        }
                    }
                }
            }
            true
        }
    }

    private fun startArrowAnimationVertical(arrow: ImageView, initialY: Float, initialScaleX: Float, initialScaleY: Float, translation: Float) {
        arrow.apply {
            alpha = 1f
            y = initialY
            scaleX = initialScaleX
            scaleY = initialScaleY
            animate()
                .alpha(0f)
                .translationY(translation)
                .scaleXBy(-0.5f)
                .scaleYBy(-0.5f)
                .setDuration(1000)
                .withEndAction {
                    if (!stopAnimation) {
                        startArrowAnimationVertical(this, initialY, initialScaleX, initialScaleY, translation)
                    }
                }
        }
    }

    private fun dialpadPressed(char: Char) {
        CallManager.keypad(char)
        binding.dialpadInput.addCharacter(char)
        maybePerformDialpadHapticFeedback(binding.dialpadInput)
    }

//    private fun openBluetoothSettings() {
//        try {
//            val storageSettingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
//            startActivity(storageSettingsIntent)
//        } catch (e: Exception) {
//            showErrorToast(e)
//        }
//    }

    private fun changeCallAudioRoute() {
        val supportAudioRoutes = CallManager.getSupportedAudioRoutes()
        if (supportAudioRoutes.size > 2) {
            createOrUpdateAudioRouteChooser(supportAudioRoutes)
        } else {
//            val isSpeakerOn = !isSpeakerOn
//            val newRoute = if (isSpeakerOn) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
//            CallManager.setAudioRoute(newRoute)
            CallManager.toggleSpeakerRoute()
        }
    }

    private fun createOrUpdateAudioRouteChooser(routes: Array<AudioRoute>, create: Boolean = true) {
        val callAudioRoute = CallManager.getCallAudioRoute()
        val items = routes
            .sortedByDescending { it.route }
            .map {
                SimpleListItem(id = it.route, textRes = it.stringRes, imageRes = it.iconRes, selected = it == callAudioRoute)
            }
            .toTypedArray()

        if (audioRoutePopupMenu != null) {
            audioRoutePopupMenu?.dismiss()
        }

        if (create) {
            val wrapper: Context = ContextThemeWrapper(this@CallActivity, getPopupMenuTheme())
            audioRoutePopupMenu = PopupMenu(wrapper, binding.callToggleSpeaker, Gravity.END)

            items.forEach { item ->
                audioRoutePopupMenu?.menu?.add(
                    1,
                    item.id,
                    item.id,
                    item.textRes ?: R.string.other
                )?.setIcon(item.imageRes ?: R.drawable.ic_transparent)
            }

            audioRoutePopupMenu?.setOnMenuItemClickListener { item ->
                CallManager.setAudioRoute(item.itemId)
                true
            }

            if (isQPlus()) {
                audioRoutePopupMenu?.setForceShowIcon(true)
            }

            audioRoutePopupMenu?.show()

            val selected = items.first { it.selected }
            val primaryColor = getProperPrimaryColor()
            val textColor = getProperTextColor()
            // icon and text coloring
            audioRoutePopupMenu?.menu?.apply {
                for (index in 0 until this.size) {
                    val item = this[index]
                    val color = if (item.itemId == selected.id) primaryColor else textColor

                    //icon coloring
                    if (isQPlus()) {
                        item.icon!!.colorFilter = BlendModeColorFilter(
                            color, BlendMode.SRC_IN
                        )
                    } else {
                        item.icon!!.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                    }

                    //text coloring
                    val spannableString = SpannableString(item.title)
                    spannableString.setSpan(
                        ForegroundColorSpan(color),
                        0,
                        spannableString.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    item.title = spannableString
                }
            }
        }
    }

    private fun updateCallAudioState(route: AudioRoute?, changeProximitySensor: Boolean = true) {
        if (route != null) {
            //If enabled, one of the users (OnePlus 13r, Oxygen 16OS) has his microphone turned off at the start of a call??
            //isMicrophoneOff = audioManager.isMicrophoneMute
            if (isMicrophoneInitialized) isMicrophoneOff = audioManager.isMicrophoneMute
            else isMicrophoneInitialized = true

            updateMicrophoneButton()

            isSpeakerOn = route == AudioRoute.SPEAKER
            val supportedAudioRoutes = CallManager.getSupportedAudioRoutes()
            binding.callToggleSpeaker.apply {
                val bluetoothConnected = supportedAudioRoutes.contains(AudioRoute.BLUETOOTH)
                val description = if (bluetoothConnected) {
                    getString(R.string.choose_audio_route)
                } else {
                    getString(if (isSpeakerOn) R.string.turn_speaker_off else R.string.turn_speaker_on)
                }
                contentDescription = description
                // show speaker icon when a headset is connected, a headset icon maybe confusing to some
                if (/*route == AudioRoute.WIRED_HEADSET || */route == AudioRoute.EARPIECE) {
                    setImageResource(R.drawable.ic_volume_down_vector)
                } else {
                    setImageResource(route.iconRes)
                }
            }
            binding.callToggleSpeakerLabel.text =
                if (supportedAudioRoutes.size == 2) getString(R.string.audio_route_speaker) else getString(route.stringRes)
            toggleButtonColor(binding.callToggleSpeaker, enabled = route != AudioRoute.EARPIECE && route != AudioRoute.WIRED_HEADSET)
            createOrUpdateAudioRouteChooser(supportedAudioRoutes, create = false)

            if (changeProximitySensor) { // No need to turn on the sensor when a call has not yet been answered
                if (isSpeakerOn) {
                    disableProximitySensor()
                } else {
                    enableProximitySensor()
                }
            }
        }
    }

    private fun toggleMicrophone() {
        isMicrophoneOff = !isMicrophoneOff

        audioManager.isMicrophoneMute = isMicrophoneOff
        CallManager.inCallService?.setMuted(isMicrophoneOff)
        updateMicrophoneButton()

        CallNotificationManager(this).updateNotification()
    }

    private fun updateMicrophoneButton() {
        binding.apply {
            val drawable = if (!isMicrophoneOff) R.drawable.ic_microphone_vector else R.drawable.ic_microphone_off_vector
            callToggleMicrophone.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, drawable))

            if (isSpecialBackground(config.backgroundCallScreen)) {
                val color = if (isMicrophoneOff) Color.WHITE else Color.GRAY
                callToggleMicrophone.background.applyColorFilter(color)
                val colorIcon = if (isMicrophoneOff) Color.BLACK else Color.WHITE
                callToggleMicrophone.applyColorFilter(colorIcon)
            }
            callToggleMicrophone.background.alpha = if (isMicrophoneOff) 255 else 60
            val microphoneDescription = getString(if (isMicrophoneOff) R.string.turn_microphone_on else R.string.turn_microphone_off)
            callToggleMicrophone.contentDescription = microphoneDescription
//            callToggleMicrophoneLabel.text = microphoneDescription
        }
    }

    private fun toggleRecording() {
        // Toggle recording (works for both manual and auto mode)
        CallManager.toggleRecording(this)
        updateRecordingButton()
        // Update visibility to refresh alpha like redial button
        updateRecordingButtonVisibility()
    }

    private fun updateRecordingButton() {
        binding.apply {
            val isRecording = CallManager.isRecording
            val isManualOnly = config.callRecordingAutoRule == RECORDING_RULE_NONE
            
            // Button is always enabled and clickable (like redial button)
            callToggleRecording.isEnabled = true
            callToggleRecording.isClickable = true
            callToggleRecording.isFocusable = true
            
            if (isSpecialBackground(config.backgroundCallScreen)) {
                val color = if (isRecording) Color.RED else Color.GRAY
                callToggleRecording.background.applyColorFilter(color)
                callToggleRecording.applyColorFilter(Color.WHITE)
            }
            
            // Background alpha based on recording state
            callToggleRecording.background.alpha = if (isRecording) 255 else 60
            
            // In manual mode: update alpha like redial button (1.0f when active, 0.5f when inactive)
            // Only update if button is visible (like redial button pattern)
            callToggleRecording.alpha = when {
                isManualOnly && callToggleRecording.isVisible -> if (isRecording) 1f else 0.5f
                !isManualOnly -> 1f
                else -> callToggleRecording.alpha // Keep current alpha
            }
            
            val recordingDescription = getString(if (isRecording) R.string.stop_recording else R.string.start_recording)
            callToggleRecording.contentDescription = recordingDescription
            callToggleRecordingLabel.text = getString(if (isRecording) R.string.recording_active else R.string.record)
        }
    }

    private fun toggleDialpadVisibility() {
        if (binding.dialpadWrapper.isVisible()) hideDialpad() else showDialpad()
    }

    private fun showDialpad() {
        binding.apply {
            dialpadWrapper.beVisible()
            dialpadClose.beVisible()
            arrayOf(
                callerAvatar, callerNameLabel, callerDescription, callerNumber, callerNotes, callStatusLabel,
                callSimImage, callSimId, callToggleMicrophone, callDialpadHolder,
                callToggleSpeaker, callAddContactHolder, callInfo, addCallerNote
            ).forEach {
                it.beGone()
            }
            controlsSingleCall.beGone()
            controlsTwoCalls.beGone()

            RxAnimation.together(
                dialpadWrapper.scale(1f),
                dialpadWrapper.fadeIn(duration = 160),
                dialpadClose.fadeIn(duration = 160)
            ).doAfterTerminate {
            }.subscribe()
        }
    }

    @SuppressLint("MissingPermission")
    private fun hideDialpad() {
        binding.apply {
            RxAnimation.together(
                dialpadWrapper.scale(0.7f),
                dialpadWrapper.fadeOut(duration = 160),
                dialpadClose.fadeOut(duration = 160)
            ).doAfterTerminate {
                dialpadWrapper.beGone()
                dialpadClose.beGone()
                arrayOf(
                    callerAvatar, callerNameLabel, callerNumber, callStatusLabel,
                    callToggleMicrophone, callDialpadHolder,
                    callToggleSpeaker
                ).forEach {
                    it.beVisible()
                }
                callerDescription.beVisibleIf(callerDescription.text.isNotEmpty())
                callerNotes.beVisibleIf(callerNotes.text.isNotEmpty())
                val accounts = telecomManager.callCapablePhoneAccounts
                callSimImage.beVisibleIf(accounts.size > 1)
                callSimId.beVisibleIf(accounts.size > 1)
                updateState()
                // Update recording button visibility after state update to ensure correct button visibility
                updateRecordingButtonVisibility()
            }.subscribe()
        }
    }

    private fun toggleHold() {
        binding.apply {
            val isOnHold = CallManager.toggleHold()
            val drawable = if (isOnHold) R.drawable.ic_pause_crossed_vector else R.drawable.ic_pause_vector
            callToggleHold.setImageDrawable(AppCompatResources.getDrawable(this@CallActivity, drawable))
            val description = getString(if (isOnHold) R.string.resume_call else R.string.hold_call)
            callToggleLabel.text = description
            callToggleHold.contentDescription = description
            holdStatusLabel.beInvisibleIf(!isOnHold)
            RxAnimation.from(holdStatusLabel)
                .shake()
                .subscribe()

            if (isSpecialBackground(config.backgroundCallScreen)) {
                val color = if (isOnHold) Color.WHITE else Color.GRAY
                callToggleHold.background.applyColorFilter(color)
                val colorIcon = if (isOnHold) Color.BLACK else Color.WHITE
                callToggleHold.applyColorFilter(colorIcon)
            }
            callToggleHold.background.alpha = if (isOnHold) 255 else 60
        }
    }

    private fun addContact() {
        val number = callContact?.number?.ifEmpty { "" } ?: ""
        val formatNumber = if (config.formatPhoneNumbers) number.formatPhoneNumber() else number
        Intent().apply {
            action = Intent.ACTION_INSERT_OR_EDIT
            type = "vnd.android.cursor.item/contact"
            putExtra(KEY_PHONE, formatNumber)
            launchActivityIntent(this)
        }
    }

    @Suppress("DEPRECATION")
    private fun updateOtherPersonsInfo(avatarUri: String, isConference: Boolean) {
        if (callContact == null) {
            return
        }

        binding.apply {
            val (name, _, number, numberLabel, description, isABusinessCall, isVoiceMail) = callContact!!
            callerNameLabel.text =
                formatterUnicodeWrap(name.ifEmpty { getString(R.string.unknown_caller) })
            if (number.isNotEmpty() && number != name) {
                // Format number with district format if applicable (from database)
                // DatabasePhoneNumberFormatter now works on main thread with caching
                val numberText = if (config.formatPhoneNumbers) {
                    formatterUnicodeWrap(number.formatPhoneNumber())
                } else {
                    formatterUnicodeWrap(number)
                }
                if (numberLabel.isNotEmpty()) {
                    val numberLabelText = formatterUnicodeWrap(numberLabel)
                    callerNumber.text = numberLabelText
                    callerNumber.setOnClickListener {
                        if (callerNumber.text == numberLabelText) callerNumber.text = numberText
                        else callerNumber.text = numberLabelText
                        maybePerformDialpadHapticFeedback(it)
                    }
                } else {
                    callerNumber.text = numberText
                }

                // Show location for database-formatted phone numbers
                // Location lookup uses the same format matching logic as formatting
                callerDescription.beGone() // Hide initially
                number.getLocationByPrefixAsync(this@CallActivity) { location ->
                    if (location != null && callContact != null && callContact!!.number == number) {
                        // If there's a description, show both description and location
                        val displayText = if (description.isNotEmpty() && description != name) {
                            "$description - $location"
                        } else {
                            location
                        }
                        callerDescription.text = formatterUnicodeWrap(displayText)
                        callerDescription.beVisible()
                    } else if (description.isNotEmpty() && description != name) {
                        // If no location found but we have description, show description
                        callerDescription.text = formatterUnicodeWrap(description)
                        callerDescription.beVisible()
                    }
                }
            } else {
                callerDescription.beGone()
                val country = number.getCountryByNumber()
                if (country != "") {
                    callerNumber.text = formatterUnicodeWrap(country)
                } else callerNumber.beGone()
            }

            callerAvatar.apply {
                // Add long-press on avatar to open tuning dialog for all background types except video
                setOnLongClickListener {
                    if (config.backgroundCallScreen != VIDEO_BACKGROUND && config.backgroundCallScreen != THEME_BACKGROUND) {
                        promptCustomBackgroundTuning()
                        true
                    } else {
                        false
                    }
                }
                
                try {
                    if (number == name || ((isABusinessCall || isVoiceMail) && avatarUri == "") || isDestroyed || isFinishing) {
                        val drawable = when {
                            isVoiceMail -> {
                                @SuppressLint("UseCompatLoadingForDrawables")
                                val drawableVoicemail = resources.getDrawable( R.drawable.placeholder_voicemail, theme)
                                if (baseConfig.useColoredContacts) {
                                    val letterBackgroundColors = getLetterBackgroundColors()
                                    val color = letterBackgroundColors[abs(name.hashCode()) % letterBackgroundColors.size].toInt()
                                    (drawableVoicemail as LayerDrawable).findDrawableByLayerId(R.id.placeholder_contact_background).applyColorFilter(color)
                                }
                                drawableVoicemail
                            }
                            isABusinessCall -> SimpleContactsHelper(this@CallActivity).getColoredCompanyIcon(name)
                            else -> SimpleContactsHelper(this@CallActivity).getColoredContactIcon(name)
                        }
                        setImageDrawable(drawable)
                    } else {
                        if (!isFinishing && !isDestroyed) {
                            val placeholder = if (isConference) {
                                SimpleContactsHelper(this@CallActivity).getColoredGroupIcon(name)
                            } else null
                            SimpleContactsHelper(this@CallActivity.applicationContext).loadContactImage(
                                avatarUri,
                                this,
                                name,
                                placeholder
                            )
                        }
                    }
                } catch (_: Exception) {
                    @SuppressLint("UseCompatLoadingForDrawables")
                    val drawable = resources.getDrawable( R.drawable.placeholder_contact, theme)
                    setImageDrawable(drawable)
                }
            }

            callMessage.apply {
                setOnClickListener {
                    val wrapper: Context = ContextThemeWrapper(this@CallActivity, getPopupMenuTheme())
                    val popupMenu = PopupMenu(wrapper, callMessage, Gravity.END)
                    val quickAnswers = config.quickAnswers
                    popupMenu.menu.add(1, 1, 1, R.string.other).setIcon(R.drawable.ic_transparent)
                    if (quickAnswers.size == 3) {
                        popupMenu.menu.add(1, 2, 2, quickAnswers[0]).setIcon(R.drawable.ic_clock_vector)
                        popupMenu.menu.add(1, 3, 3, quickAnswers[1]).setIcon(R.drawable.ic_run)
                        popupMenu.menu.add(1, 4, 4, quickAnswers[2]).setIcon(R.drawable.ic_microphone_off_vector)
                    }
                    popupMenu.setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> {
                                sendSMS(callContact!!.number)
                                endCall()
                            }

                            else -> {
                                endCall(rejectWithMessage = true, textMessage = item.title.toString())
                            }
                        }
                        true
                    }
                    if (isQPlus()) {
                        popupMenu.setForceShowIcon(true)
                    }
                    popupMenu.show()
                    // icon coloring
                    popupMenu.menu.apply {
                        for (index in 0 until this.size) {
                            val item = this[index]

                            if (isQPlus()) {
                                item.icon!!.colorFilter = BlendModeColorFilter(
                                    getProperTextColor(), BlendMode.SRC_IN
                                )
                            } else {
                                item.icon!!.setColorFilter(getProperTextColor(), PorterDuff.Mode.SRC_IN)
                            }
                        }
                    }

                    //sendSMS(callContact!!.number, "textMessage")
                }
                setOnLongClickListener { toast(R.string.send_sms); true; }
            }

            callRemind.apply {
                setOnClickListener {
                    this@CallActivity.handleNotificationPermission { permission ->
                        if (permission) {
                            val wrapper: Context = ContextThemeWrapper(this@CallActivity, getPopupMenuTheme())
                            val popupMenu = PopupMenu(wrapper, callRemind, Gravity.START)
                            popupMenu.menu.add(1, 1, 1, String.format(resources.getQuantityString(R.plurals.minutes, 10, 10)))
                            popupMenu.menu.add(1, 2, 2, String.format(resources.getQuantityString(R.plurals.minutes, 30, 30)))
                            popupMenu.menu.add(1, 3, 3, String.format(resources.getQuantityString(R.plurals.minutes, 60, 60)))
                            popupMenu.setOnMenuItemClickListener { item ->
                                when (item.itemId) {
                                    1 -> {
                                        startTimer(600)
                                        endCall()
                                    }

                                    2 -> {
                                        startTimer(1800)
                                        endCall()
                                    }

                                    else -> {
                                        startTimer(3600)
                                        endCall()
                                    }
                                }
                                true
                            }
                            popupMenu.show()
                        } else {
                            toast(R.string.allow_notifications_reminders)
                        }
                    }
                }
                setOnLongClickListener { toast(R.string.remind_me); true; }
            }

            val callNote = callerNotesHelper.getCallerNotes(number)
            callerNotes.apply {
                beVisibleIf(callNote != null && !isConference)
                if (callNote != null) {
                    text = callNote.note
                }
                setOnClickListener {
                    changeNoteDialog(number)
                }
            }

            addCallerNote.apply {
                setOnClickListener {
                    changeNoteDialog(number)
                }
            }
        }
    }

    private fun changeNoteDialog(number: String) {
        val callerNote = callerNotesHelper.getCallerNotes(number)
        ChangeTextDialog(
            activity = this@CallActivity,
            title = number.normalizeString(),
            currentText = callerNote?.note,
            maxLength = CALLER_NOTES_MAX_LENGTH,
            blurTarget = blurTarget,
            showNeutralButton = true,
            neutralTextRes = R.string.delete
        ) {
            if (it != "") {
                callerNotesHelper.addCallerNotes(number, it, callerNote) {
                    binding.callerNotes.text = it
                    binding.callerNotes.beVisible()
                }
            } else {
                callerNotesHelper.deleteCallerNotes(callerNote) {
                    binding.callerNotes.text = it
                    binding.callerNotes.beGone()
                }
            }
        }
    }

    private fun startTimer(duration: Int) {
        timerHelper.getTimers { timers ->
            val runningTimers = timers.filter { it.state is TimerState.Running && it.id == 1 }
            runningTimers.forEach { timer ->
                EventBus.getDefault().post(TimerEvent.Delete(timer.id!!))
            }
            val newTimer = createNewTimer()
            newTimer.id = 1
            newTimer.title = callContact!!.name
            newTimer.label = callContact!!.number
            newTimer.seconds = duration
            timerHelper.insertOrUpdateTimer(newTimer)
            EventBus.getDefault().post(TimerEvent.Start(1, duration.secondsToMillis))
        }
    }

    private val Int.secondsToMillis get() = TimeUnit.SECONDS.toMillis(this.toLong())

    private fun sendSMS(number: String, text: String = " ") {
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.fromParts("smsto", number, null)
            putExtra("sms_body", text)
            launchActivityIntent(this)
        }
    }

    private fun getContactNameOrNumber(contact: CallContact): String {
        return contact.name.ifEmpty {
            contact.number.ifEmpty {
                getString(R.string.unknown_caller)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkCalledSIMCard() {
        try {
            val simLabels = getAvailableSIMCardLabels()
            if (simLabels.size > 1) {
                simLabels.forEachIndexed { index, sim ->
                    if (sim.handle == CallManager.getPrimaryCall()?.details?.accountHandle) {
                        binding.apply {
                            callSimId.text = sim.id.toString()
                            callSimId.beVisible()
                            callSimImage.beVisible()
                            val simColor = sim.color
                            callSimId.setTextColor(simColor.getContrastColor())
                            callSimImage.applyColorFilter(simColor)
                        }

                        val acceptDrawableId = when (index) {
                            0 -> R.drawable.ic_phone_one_vector
                            1 -> R.drawable.ic_phone_two_vector
                            else -> R.drawable.ic_phone_vector
                        }
                        val acceptDrawable = AppCompatResources.getDrawable(this@CallActivity, acceptDrawableId)

                        val rippleBg = AppCompatResources.getDrawable(this@CallActivity, R.drawable.ic_call_accept) as RippleDrawable
                        val layerDrawable = rippleBg.findDrawableByLayerId(R.id.accept_call_background_holder) as LayerDrawable
                        layerDrawable.setDrawableByLayerId(R.id.accept_call_icon, acceptDrawable)
                        binding.callAccept.setImageDrawable(rippleBg)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun updateCallState(call: Call) {
        val callDetails = call.details
        val connectTimeMillis: Long = callDetails.connectTimeMillis
        val isBusy = connectTimeMillis.toInt() == 0

        val state = call.getStateCompat()
        when (state) {
            Call.STATE_RINGING -> callRinging()
            Call.STATE_ACTIVE -> callStarted()
            Call.STATE_DISCONNECTED -> endCall(isBusy = isBusy)
            Call.STATE_CONNECTING, Call.STATE_DIALING -> initOutgoingCallUI()
            Call.STATE_SELECT_PHONE_ACCOUNT -> showPhoneAccountPicker()
        }

        val statusTextId = when (state) {
            Call.STATE_RINGING -> R.string.is_calling
            Call.STATE_CONNECTING, Call.STATE_DIALING -> R.string.dialing
            Call.STATE_DISCONNECTED -> if (isBusy) R.string.busy else 0
            else -> 0
        }

        binding.apply {
            if (statusTextId != 0) {
                callStatusLabel.text = getString(statusTextId)
            }

            callInfo.beVisibleIf(!isCallEnded && call.hasCapability(Call.Details.CAPABILITY_MANAGE_CONFERENCE))
            addCallerNote.beVisibleIf(!callInfo.isVisible)
            if (dialpadWrapper.isGone()) {
                setActionButtonEnabled(callSwapHolder, enabled = !isCallEnded && state == Call.STATE_ACTIVE)
                setActionButtonEnabled(callMergeHolder, enabled = !isCallEnded && state == Call.STATE_ACTIVE)
            }
            updateRedialButtonVisibility()
        }
    }

    private fun updateState() {
        val phoneState = CallManager.getPhoneState()
        var changeProximitySensor = true
        if (phoneState is SingleCall) {
            updateCallState(phoneState.call)
            updateCallOnHoldState(null)
            val state = phoneState.call.getStateCompat()
            val isSingleCallActionsEnabled = !isCallEnded && (state == Call.STATE_ACTIVE || state == Call.STATE_DISCONNECTED
                || state == Call.STATE_DISCONNECTING || state == Call.STATE_HOLDING)
            if (binding.dialpadWrapper.isGone()) {
                setActionImageViewEnabled(binding.callToggleHold, isSingleCallActionsEnabled)
                setActionButtonEnabled(binding.callAddHolder, isSingleCallActionsEnabled)
            }
            if (state == Call.REJECT_REASON_UNWANTED) changeProximitySensor = false
        } else if (phoneState is TwoCalls) {
            updateCallState(phoneState.active)
            updateCallOnHoldState(phoneState.onHold, phoneState.active)
        }

        runOnUiThread {
            updateCallAudioState(CallManager.getCallAudioRoute(), changeProximitySensor)
            updateMicrophoneButton()
            updateRecordingButton()
            updateRecordingButtonVisibility()
            updateRedialButtonVisibility()
        }
    }

    private fun updateCallOnHoldState(call: Call?, callActive: Call? = null) {
        val hasCallOnHold = call != null
        if (hasCallOnHold) {
            getCallContact(applicationContext, call) { contact ->
                runOnUiThread {
                    binding.onHoldCallerName.text = getContactNameOrNumber(contact)
                }
            }

            // A second call has been received but not yet accepted
            if (call.getStateCompat() == Call.REJECT_REASON_UNWANTED) {
                binding.apply {
                    ongoingCallHolder.beGone()
                    incomingCallHolder.beVisible()
                    callStatusLabel.text = getString(R.string.is_calling)
                    RxAnimation.from(binding.callStatusLabel)
                        .shake()
                        .subscribe()

                    arrayOf(
                        callDraggable, callDraggableBackground, callDraggableVertical,
                        callLeftArrow, callRightArrow,
                        callUpArrow, callDownArrow
                    ).forEach {
                        it.beGone()
                    }


                    callDecline.beVisible()
                    callDecline.setOnClickListener {
                        endCall()
                    }

                    callAccept.beVisible()
                    callAccept.setOnClickListener {
                        acceptCall()
                    }

                    callAcceptAndDecline.apply {
                        beVisible()
                        setText(R.string.answer_end_other_call)
                        setOnClickListener {
                            acceptCall()
                            callActive?.disconnect()
                        }
                    }
                }
            }
        } else {
            if (config.callBlockButton) binding.callAcceptAndDecline.apply {
                beVisible()
                setText(R.string.block_number)
                setOnClickListener {
                    if (callContact != null) {
                        val number = callContact!!.number
                        val baseString = R.string.block_confirmation
                        val question = String.format(resources.getString(baseString), number)

                        ConfirmationAdvancedDialog(this@CallActivity, question, cancelOnTouchOutside = false, blurTarget = binding.mainBlurTarget) {
                            if (it) {
                                blockNumbers(number.normalizePhoneNumber())
                            }
                        }
                    }
                }
            }
        }

        binding.apply {
            onHoldStatusHolder.beVisibleIf(hasCallOnHold)
            controlsSingleCall.beVisibleIf(!hasCallOnHold && dialpadWrapper.isGone())
            controlsTwoCalls.beVisibleIf(hasCallOnHold && dialpadWrapper.isGone())
        }
    }

    private fun blockNumbers(number: String) {
        config.needRestart = true
        if (addBlockedNumber(number)) endCall()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("UseCompatLoadingForDrawables")
    private fun updateCallContactInfo(call: Call?) {
        binding.callDetails.beVisibleIf(call.isHD())
        getCallContact(applicationContext, call) { contact ->
            if (call != CallManager.getPrimaryCall()) {
                return@getCallContact
            }
            callContact = contact

            val configBackgroundCallScreen = config.backgroundCallScreen
            val isConference = call.isConference()

            var drawable: Drawable? = null
            if (configBackgroundCallScreen == BLUR_AVATAR || configBackgroundCallScreen == AVATAR) {
                val avatar =
                    if (!isConference) callContactAvatarHelper.getCallContactAvatar(contact.photoUri, false) else null
                if (avatar != null) {
                    val bg = when (configBackgroundCallScreen) {
                        BLUR_AVATAR -> BlurFactory.fileToBlurBitmap(avatar, this, 0.6f, 5f)
                        AVATAR -> avatar
                        else -> null
                    }
                    val windowHeight = binding.callHolder.height //window.decorView.height
                    val windowWidth = binding.callHolder.width //window.decorView.width
                    if (bg != null && windowWidth != 0) {
                        val aspectRatio = windowHeight / windowWidth
                        val aspectRatioNotZero = if (aspectRatio == 0) 1 else aspectRatio
                        drawable = bg.cropCenter(bg.width / aspectRatioNotZero, bg.height)?.toDrawable(resources)
                    }
                }
            }

            runOnUiThread {
                if (drawable != null) {
                    binding.callHolder.background = drawable
                    binding.callHolder.background.alpha = 60
                    if (isQPlus()) {
                        binding.callHolder.background.colorFilter = BlendModeColorFilter(Color.DKGRAY, BlendMode.SOFT_LIGHT)
                    } else {
                        binding.callHolder.background.setColorFilter(Color.DKGRAY, PorterDuff.Mode.DARKEN)
                    }
                }

                val avatarRound = if (!isConference) contact.photoUri else ""
                updateOtherPersonsInfo(avatarRound, isConference)
                checkCalledSIMCard()
            }
        }
    }

    private fun acceptCall() {
        CallManager.accept()
    }

    private fun initOutgoingCallUI() {
        enableProximitySensor()
        binding.incomingCallHolder.beGone()
        binding.ongoingCallHolder.beVisible()
        binding.callEnd.beVisible()
        updateRedialButtonVisibility()
    }

    private fun callRinging() {
        binding.incomingCallHolder.beVisible()
        enableShakeDetection()
    }

    private fun callStarted() {
        enableProximitySensor()
        disableShakeDetection()
        binding.incomingCallHolder.beGone()
        binding.ongoingCallHolder.beVisible()
        binding.callEnd.beVisible()
        callDurationHandler.removeCallbacks(updateCallDurationTask)
        callDurationHandler.post(updateCallDurationTask)
        updateRedialButtonVisibility()
        updateRecordingButtonVisibility()
        maybePerformCallHapticFeedback(binding.callerNameLabel)
//        if (config.flashForAlerts) MyCameraImpl.newInstance(this).toggleSOS()
    }

    private fun updateRecordingButtonVisibility() {
        val primaryCall = CallManager.getPrimaryCall()
        val callState = primaryCall?.getStateCompat()
        val isActiveCall = !isCallEnded && callState == Call.STATE_ACTIVE
        val shouldShow = config.callRecordingEnabled && isActiveCall
        val isManualOnly = config.callRecordingAutoRule == RECORDING_RULE_NONE
        
        binding.apply {
            if (shouldShow) {
                callToggleRecording.beVisible()
                callToggleRecordingLabel.beVisible()
                // Hide add contact button when recording button is shown
                callAddContactHolder.beGone()
                
                // Set button enabled and alpha like redial button
                callToggleRecording.isEnabled = true
                // In manual mode: use alpha like redial button (1.0f when active, 0.5f when inactive)
                if (isManualOnly) {
                    callToggleRecording.alpha = if (CallManager.isRecording) 1f else 0.5f
                } else {
                    callToggleRecording.alpha = 1f
                }
            } else {
                callToggleRecording.beGone()
                callToggleRecordingLabel.beGone()
                // Show add contact button when recording button is hidden (if other conditions allow)
                callAddContactHolder.beVisibleIf(config.callButtonStyle == IOS16 && !isSmallScreen && dialpadWrapper.isGone())
            }
        }
    }

    private fun showPhoneAccountPicker() {
        if (callContact != null && !needSelectSIM) {
            getHandleToUse(intent, callContact!!.number) { handle ->
                CallManager.getPrimaryCall()?.phoneAccountSelected(handle, false)
            }
        }
    }

    private fun endCall(rejectWithMessage: Boolean = false, textMessage: String? = null, isBusy: Boolean = false) {
        CallManager.reject(rejectWithMessage, textMessage)
        disableShakeDetection()

        if (isBusy) toast(R.string.busy)

        if (isCallEnded) {
            safeFinishAndRemoveTask()
            return
        }

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }

        isCallEnded = true
        runOnUiThread {
            val phoneState = CallManager.getPhoneState()
            updateRedialButtonVisibility()
            if (callDuration > 0) {
                disableAllActionButtons()
                @SuppressLint("SetTextI18n")
                val label = "${callDuration.getFormattedDuration()} (${getString(R.string.call_ended)})"
                binding.callStatusLabel.text = label
                safeFinishAndRemoveTask()
                if (phoneState is TwoCalls) startActivity(Intent(this, CallActivity::class.java))
            } else {
                disableAllActionButtons()
                binding.callStatusLabel.text = getString(R.string.call_ended)
                if (phoneState is TwoCalls) {
                    safeFinishAndRemoveTask()
                    startActivity(Intent(this, CallActivity::class.java))
                } else finish()
            }
            if (phoneState is SingleCall) disableProximitySensor()
        }
        maybePerformCallHapticFeedback(binding.callerNameLabel)
    }

    private fun safeFinishAndRemoveTask() {
        try {
            if (intent != null) {
                finishAndRemoveTask()
            } else {
                finish()
            }
        } catch (_: Exception) {
            finish()
        }
    }

    private val callCallback = object : CallManagerListener {
        override fun onStateChanged() {
            updateState()
        }

        override fun onAudioStateChanged(audioState: AudioRoute) {
            updateCallAudioState(audioState)
        }

        override fun onPrimaryCallChanged(call: Call) {
            callDurationHandler.removeCallbacks(updateCallDurationTask)
            updateCallContactInfo(call)
            updateState()
        }
    }

    private val updateCallDurationTask = object : Runnable {
        override fun run() {
            val call = CallManager.getPrimaryCall()
            callDuration = call.getCallDuration()
            if (!isCallEnded && call.getStateCompat() != Call.REJECT_REASON_UNWANTED) {
                binding.callStatusLabel.text = callDuration.getFormattedDuration()
                callDurationHandler.postDelayed(this, 1000)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun addLockScreenFlags() {
        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        try {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            screenOnWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "com.android.dialer:full_wake_lock"
            )
            screenOnWakeLock!!.acquire(10 * 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun enableProximitySensor() {
        if (!config.disableProximitySensor && (proximityWakeLock == null || proximityWakeLock?.isHeld == false)) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "com.android.dialer:wake_lock")
            proximityWakeLock!!.acquire(60 * MINUTE_SECONDS * 1000L)
        }
    }

    private fun disableProximitySensor() {
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock!!.release()
        }
    }

    private fun enableShakeDetection() {
        if (!config.shakeToAnswer) return
        
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            
            if (accelerometer != null && shakeDetector == null) {
                shakeDetector = ShakeDetector(this@CallActivity) {
                    // Only answer if call is still ringing and it's an incoming call
                    val primaryCall = CallManager.getPrimaryCall()
                    if (primaryCall != null) {
                        val callState = primaryCall.getStateCompat()
                        if (callState == Call.STATE_RINGING && !primaryCall.isOutgoing()) {
                            acceptCall()
                        }
                    }
                }
                sensorManager?.registerListener(shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun disableShakeDetection() {
        try {
            shakeDetector?.let {
                sensorManager?.unregisterListener(it)
            }
            shakeDetector = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private class ShakeDetector(
        private val context: Context,
        private val onShake: () -> Unit
    ) : SensorEventListener {
        private val SHAKE_THRESHOLD = 12.0f
        private val SHAKE_SLOP_TIME_MS = 500
        private var lastShakeTime = 0L
        private var lastX = 0f
        private var lastY = 0f
        private var lastZ = 0f
        private var vibrator: Vibrator? = null

        init {
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        override fun onSensorChanged(event: SensorEvent) {
            val currentTime = System.currentTimeMillis()
            
            if (currentTime - lastShakeTime > SHAKE_SLOP_TIME_MS) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val deltaX = abs(x - lastX)
                val deltaY = abs(y - lastY)
                val deltaZ = abs(z - lastZ)

                if (deltaX > SHAKE_THRESHOLD || deltaY > SHAKE_THRESHOLD || deltaZ > SHAKE_THRESHOLD) {
                    lastShakeTime = currentTime
                    performVibration()
                    onShake()
                }

                lastX = x
                lastY = y
                lastZ = z
            }
        }

        private fun performVibration() {
            try {
                if (isOreoPlus()) {
                    val vibrationEffect = VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                    vibrator?.vibrate(vibrationEffect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(100)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            // Not used
        }
    }

    private fun updateRedialButtonVisibility() {
        val lastNumber = CallManager.getLastDialedNumber()
        val primaryCall = CallManager.getPrimaryCall()
        val callState = primaryCall?.getStateCompat()
        
        // Show redial button when auto redial setting is enabled (to allow toggling active state)
        // Or when auto redial setting is disabled and there's a last number to redial
        val shouldShowRedial: Boolean
        if (config.autoRedialEnabled) {
            // Show redial button when auto redial setting is enabled (acts as toggle for active state)
            val isOutgoingCall = callState == Call.STATE_CONNECTING || callState == Call.STATE_DIALING
            val isCallEndedState = isCallEnded && !lastNumber.isNullOrEmpty()
            shouldShowRedial = (isOutgoingCall || isCallEndedState) && !lastNumber.isNullOrEmpty()
        } else {
            // Show redial button during outgoing calls (CONNECTING, DIALING) or when call ended
            val isOutgoingCall = callState == Call.STATE_CONNECTING || callState == Call.STATE_DIALING
            val isCallEndedState = isCallEnded && !lastNumber.isNullOrEmpty()
            shouldShowRedial = (isOutgoingCall || isCallEndedState) && !lastNumber.isNullOrEmpty()
        }
        
        // Show add call button only when call is active (connected) and not ended
        val shouldShowAddCall = !isCallEnded && callState == Call.STATE_ACTIVE
        
        binding.apply {
            callRedialHolder.beVisibleIf(shouldShowRedial)
            callRedialLabel.beVisibleIf(shouldShowRedial)
            if (shouldShowRedial) {
                callRedialHolder.isEnabled = true
                // Show visual feedback: dimmed when auto redial is inactive (if setting is enabled)
                if (config.autoRedialEnabled) {
                    callRedialHolder.alpha = if (CallManager.isAutoRedialActive()) 1f else 0.5f
                } else {
                    callRedialHolder.alpha = 1f
                }
            }
            
            // Hide/show add call button based on call state
            callAddHolder.beVisibleIf(shouldShowAddCall)
            callAddLabel.beVisibleIf(shouldShowAddCall)
        }
    }

    private fun disableAllActionButtons() {
        (binding.ongoingCallHolder.children + binding.callEnd)
            .filter { it is ImageView && it.isVisible() }
            .forEach { view ->
                setActionButtonEnabled(button = view as ImageView, enabled = false)
            }
        (binding.ongoingCallHolder.children)
            .filter { it is LinearLayout && it.isVisible() }
            .forEach { view ->
                setActionButtonEnabled(button = view as LinearLayout, enabled = false)
            }
        // Keep redial button enabled even when other buttons are disabled
        binding.callRedialHolder.let {
            if (it.isVisible) {
                it.isEnabled = true
                it.alpha = 1f
            }
        }
    }

    private fun setActionButtonEnabled(button: LinearLayout, enabled: Boolean) {
        button.apply {
            isEnabled = enabled
            alpha = if (enabled) 1.0f else LOWER_ALPHA
        }
    }

    private fun setActionButtonEnabled(button: ImageView, enabled: Boolean) {
        button.apply {
            isEnabled = enabled
            alpha = if (enabled) 1.0f else LOWER_ALPHA
        }
    }

    private fun setActionImageViewEnabled(button: ImageView, enabled: Boolean) {
        button.apply {
            isEnabled = enabled
            alpha = if (enabled) 1.0f else LOWER_ALPHA
        }
    }

    private fun toggleButtonColor(view: ImageView, enabled: Boolean) {
        if (isSpecialBackground(config.backgroundCallScreen)) {
            val color = if (enabled) Color.WHITE else Color.GRAY
            view.background.applyColorFilter(color)
            val colorIcon = if (enabled) Color.BLACK else Color.WHITE
            view.applyColorFilter(colorIcon)
        }
        view.background.alpha = if (enabled) 255 else 60
    }
    
    // Helper function to check if background is special (transparent/blur/avatar)
    private fun isSpecialBackground(bgScreen: Int): Boolean {
        return bgScreen == TRANSPARENT_BACKGROUND || bgScreen == BLUR_AVATAR || bgScreen == AVATAR
    }

    /**
     * Loads and caches the blurred wallpaper drawable.
     * Uses cached version if available to avoid repeated expensive operations.
     * @param blurRadiusOverride Optional blur radius override. If null, uses config value.
     */
    private fun getBlurredWallpaperDrawable(blurRadiusOverride: Float? = null): Drawable? {
        val blurRadius = blurRadiusOverride ?: config.backgroundCallCustomBlurRadius.coerceIn(0f, 25f)
        
        // Only use cache if blur radius matches (or if no override and using default)
        if (cachedWallpaperDrawable != null && blurRadiusOverride == null) {
            return cachedWallpaperDrawable
        }
        
        return try {
            val wallpaperManager = WallpaperManager.getInstance(this)
            @SuppressLint("MissingPermission")
            val wallpaperDrawable = wallpaperManager.drawable ?: return null
            
            // If blur radius is 0, use wallpaper directly without blur
            val drawable = if (blurRadius <= 0f) {
                wallpaperDrawable
            } else {
                // Apply blur effect
                val wallpaperBlur = BlurFactory.fileToBlurBitmap(wallpaperDrawable, this, 0.2f, blurRadius)
                wallpaperBlur?.toDrawable(resources) ?: wallpaperDrawable
            }
            
            drawable.alpha = 255
            drawable.colorFilter = null
            // Only cache if using config value (no override)
            if (blurRadiusOverride == null) {
                cachedWallpaperDrawable = drawable
            }
            drawable
        } catch (_: Exception) {
            null
        }
    }

    private fun applyCustomBackground(alphaOverride: Int? = null, blurRadiusOverride: Float? = null) {
        val imagePath = config.backgroundCallCustomImage
        if (imagePath.isEmpty()) {
            // Clear cache if custom background is no longer set
            cachedCustomBackgroundBitmap?.recycle()
            cachedCustomBackgroundBitmap = null
            return
        }

        try {
            val alpha = (alphaOverride ?: config.backgroundCallCustomAlpha).coerceIn(0, 255)
            
            // Always show wallpaper as base layer with blur effect (uses cached version if available)
            val wallpaperDrawable = getBlurredWallpaperDrawable()
            
            // Use cached bitmap if available, otherwise decode
            val bitmap = cachedCustomBackgroundBitmap ?: decodeCustomBackground(imagePath) ?: run {
                // If no custom image, just show wallpaper or black
                binding.callHolder.background = wallpaperDrawable ?: ColorDrawable(Color.BLACK)
                return
            }
            
            // Cache the bitmap for future use (real-time preview)
            if (cachedCustomBackgroundBitmap == null) {
                cachedCustomBackgroundBitmap = bitmap
            }
            
            val blurRadius = (blurRadiusOverride ?: config.backgroundCallCustomBlurRadius).coerceIn(0f, 25f).let {
                if (it == 0f) CUSTOM_BACKGROUND_BLUR_RADIUS_DEFAULT else it
            }

            val blurred = BlurFactory.fileToBlurBitmap(
                bitmap,
                this,
                CUSTOM_BACKGROUND_BLUR_SCALE,
                blurRadius
            ) ?: bitmap
            val customDrawable: Drawable = blurred.toDrawable(resources)
            customDrawable.alpha = alpha
            // Remove dark overlay for custom background - user controls opacity via alpha slider
            customDrawable.colorFilter = null
            
            // Layer the backgrounds: wallpaper as base, custom image on top
            if (wallpaperDrawable != null) {
                val layers = arrayOf(wallpaperDrawable, customDrawable)
                val layerDrawable = LayerDrawable(layers)
                binding.callHolder.background = layerDrawable
            } else {
                // Fallback to just custom background if wallpaper unavailable
                binding.callHolder.background = customDrawable
            }
        } catch (_: Exception) {
        }
    }

    private fun applyTransparentBackground(blurRadiusOverride: Float? = null) {
        try {
            // Use cached wallpaper drawable if available, with optional blur override
            val drawable = getBlurredWallpaperDrawable(blurRadiusOverride)
            if (drawable != null) {
                binding.callHolder.background = drawable
                binding.callHolder.background.alpha = 255  // Full opacity
                // Remove dark overlay - show wallpaper with blur but no overlay
                binding.callHolder.background.colorFilter = null
            } else {
                binding.callHolder.setBackgroundColor(Color.BLACK)
            }
        } catch (_: Exception) {
            binding.callHolder.setBackgroundColor(Color.BLACK)
        }
    }

    private fun applyVideoBackground() {
        val videoUriString = config.backgroundCallCustomVideo
        if (videoUriString.isEmpty()) return

        try {
            if (videoBackgroundView == null) {
                videoBackgroundView = android.widget.VideoView(this).apply {
                    layoutParams = ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.MATCH_PARENT,
                        ConstraintLayout.LayoutParams.MATCH_PARENT
                    )
                    setZOrderMediaOverlay(false)
                }
                binding.callHolder.addView(videoBackgroundView, 0)
            }

            val videoView = videoBackgroundView ?: return
            videoView.setOnPreparedListener { mediaPlayer ->
                try {
                    mediaPlayer.isLooping = true
                    mediaPlayer.setVolume(0f, 0f)
                    // Scale video to fill the screen (crop if necessary to maintain aspect ratio)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    }
                } catch (_: Exception) {
                }
                videoView.start()
            }
            
            videoView.setVideoURI(Uri.parse(videoUriString))
            videoView.start()
        } catch (_: Exception) {
            // Fallback to black if anything fails
            videoBackgroundView?.stopPlayback()
            binding.callHolder.setBackgroundColor(Color.BLACK)
        }
    }

    private fun promptCustomBackgroundTuning() {
        // Don't show dialog for video background or theme background
        val backgroundType = config.backgroundCallScreen
        if (backgroundType == VIDEO_BACKGROUND || backgroundType == THEME_BACKGROUND) return
        val bindingDialog = DialogCustomBackgroundTuningBinding.inflate(layoutInflater)
        val view = bindingDialog.root

        val blurView = view.findViewById<BlurView>(R.id.blurView)
        val decorView = window.decorView
        val windowBackground = decorView.background

        blurView?.setOverlayColor(getProperBlurOverlayColor())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)

        val primaryColor = getProperPrimaryColor()

        // Set dialog title based on background type
        val titleText = when (backgroundType) {
            CUSTOM_BACKGROUND -> getString(R.string.custom_image)
            TRANSPARENT_BACKGROUND -> getString(R.string.blurry_wallpaper)
            BLUR_AVATAR -> getString(R.string.blurry_contact_photo)
            AVATAR -> getString(R.string.contact_photo)
            BLACK_BACKGROUND -> getString(R.string.black)
            else -> getString(R.string.custom_image) // Fallback (should never be reached)
        }
        
        bindingDialog.dialogTitle.apply {
            beVisible()
            text = titleText
        }

        // Initialize values based on background type
        val alphaInitial = when (backgroundType) {
            CUSTOM_BACKGROUND -> config.backgroundCallCustomAlpha.coerceIn(0, 255)
            TRANSPARENT_BACKGROUND -> 255  // Wallpaper alpha (not tunable)
            BLUR_AVATAR, AVATAR -> 60  // Avatar background alpha
            else -> 255
        }
        
        val blurInitial = when (backgroundType) {
            CUSTOM_BACKGROUND -> config.backgroundCallCustomBlurRadius.coerceIn(0f, 25f)
            TRANSPARENT_BACKGROUND -> config.backgroundCallCustomBlurRadius.coerceIn(0f, 25f)  // Use saved blur value
            BLUR_AVATAR -> 5f  // Avatar blur
            else -> 0f
        }

        var alphaValue = alphaInitial
        var blurValue = blurInitial

        // Configure alpha slider (hide for TRANSPARENT_BACKGROUND)
        if (backgroundType == TRANSPARENT_BACKGROUND) {
            bindingDialog.alphaLabel.beGone()
            bindingDialog.alphaSeek.beGone()
        } else {
            bindingDialog.alphaLabel.beVisible()
            bindingDialog.alphaSeek.beVisible()
            bindingDialog.alphaLabel.text = getString(R.string.custom_background_alpha_label, alphaValue)
            bindingDialog.alphaSeek.configure(
                rangeStart = 0f,
                rangeEnd = 255f,
                initial = alphaInitial.toFloat()
            ) { value ->
                val intVal = value.roundToInt().coerceIn(0, 255)
                alphaValue = intVal
                bindingDialog.alphaLabel.text = getString(R.string.custom_background_alpha_label, intVal)
                // Real-time preview: update background immediately
                updateBackgroundPreview(backgroundType, intVal, blurValue)
            }
        }

        // Configure blur slider
        bindingDialog.blurLabel.text = getString(R.string.custom_background_blur_label, blurInitial.roundToInt())
        bindingDialog.blurSeek.configure(
            rangeStart = 0f,
            rangeEnd = 25f,
            initial = blurInitial
        ) { value ->
            val floatVal = value.coerceIn(0f, 25f)
            blurValue = floatVal
            bindingDialog.blurLabel.text = getString(R.string.custom_background_blur_label, floatVal.roundToInt())
            // Real-time preview: update background immediately
            // For TRANSPARENT_BACKGROUND, only use blur (alpha is fixed at 255)
            val previewAlpha = if (backgroundType == TRANSPARENT_BACKGROUND) 255 else alphaValue
            updateBackgroundPreview(backgroundType, previewAlpha, floatVal)
        }

        getAlertDialogBuilder()
            .apply {
                // Pass empty titleText to prevent setupDialogStuff from adding title outside BlurView
                setupDialogStuff(view, this, titleText = "") { alertDialog ->
                    val positiveButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
                    val negativeButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
                    val buttonsContainer = view.findViewById<android.widget.LinearLayout>(R.id.buttons_container)

                    buttonsContainer?.visibility = android.view.View.VISIBLE

                    positiveButton?.apply {
                        setTextColor(primaryColor)
                        setOnClickListener {
                            // Save the values to config based on background type
                            when (backgroundType) {
                                CUSTOM_BACKGROUND -> {
                                    config.backgroundCallCustomAlpha = alphaValue
                                    config.backgroundCallCustomBlurRadius = blurValue.coerceIn(0f, 25f)
                                    applyCustomBackground()
                                }
                                TRANSPARENT_BACKGROUND -> {
                                    // Save blur value for transparent background
                                    config.backgroundCallCustomBlurRadius = blurValue.coerceIn(0f, 25f)
                                    // Clear cached wallpaper so it regenerates with new blur
                                    cachedWallpaperDrawable = null
                                    applyTransparentBackground()
                                }
                                BLUR_AVATAR, AVATAR -> {
                                    // For avatar backgrounds, update the background
                                    updateCallContactInfo(CallManager.getPrimaryCall())
                                }
                            }
                            alertDialog.dismiss()
                        }
                    }

                    negativeButton?.apply {
                        beVisible()
                        setTextColor(primaryColor)
                        setOnClickListener {
                            // Restore original values on cancel
                            when (backgroundType) {
                                CUSTOM_BACKGROUND -> applyCustomBackground()
                                TRANSPARENT_BACKGROUND -> applyTransparentBackground()
                                BLUR_AVATAR, AVATAR -> updateCallContactInfo(CallManager.getPrimaryCall())
                            }
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }
    
    private fun updateBackgroundPreview(backgroundType: Int, alpha: Int, blur: Float) {
        when (backgroundType) {
            CUSTOM_BACKGROUND -> {
                applyCustomBackground(alphaOverride = alpha, blurRadiusOverride = blur)
            }
            TRANSPARENT_BACKGROUND -> {
                // Update transparent background with new blur (alpha is always 255)
                applyTransparentBackground(blurRadiusOverride = blur)
            }
            BLUR_AVATAR, AVATAR -> {
                // Update avatar background alpha
                if (binding.callHolder.background != null) {
                    binding.callHolder.background.alpha = alpha
                }
            }
        }
    }

    private fun decodeCustomBackground(path: String): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        val metrics = resources.displayMetrics
        options.inSampleSize = calculateInSampleSize(options, metrics.widthPixels, metrics.heightPixels)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(PERMISSION_READ_STORAGE)) {
                config.backgroundCallScreen = BLUR_AVATAR
                toast(R.string.no_storage_permissions)
            }
        }
    }

    private fun maybePerformDialpadHapticFeedback(view: View?) {
        if (config.callVibration) {
            view?.performHapticFeedback()
        }
    }

    private fun maybePerformCallHapticFeedback(view: View?) {
        if (config.callStartEndVibration) {
            view?.performHapticFeedback()
        }
    }
}
