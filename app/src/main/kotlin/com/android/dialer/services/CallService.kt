package com.android.dialer.services

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.util.Log
import androidx.annotation.RequiresApi
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.canUseFullScreenIntent
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import com.android.dialer.activities.CallActivity
import com.android.dialer.extensions.config
import com.android.dialer.extensions.getStateCompat
import com.android.dialer.extensions.isOutgoing
import com.android.dialer.extensions.keyguardManager
import com.android.dialer.extensions.powerManager
import com.android.dialer.helpers.*
import com.android.dialer.models.Events
import com.android.dialer.recording.CallRecorder
import com.android.dialer.recording.RecordingNotificationManager
import com.android.dialer.recording.AutoRecordingHelper
import com.android.dialer.utils.CCFeeClass
import org.greenrobot.eventbus.EventBus

class CallService : InCallService() {
    private val context = this
    private val callNotificationManager by lazy { CallNotificationManager(this) }
    
    // Call recording
    private var callRecorder: CallRecorder? = null
    private val recordingNotificationManager by lazy { RecordingNotificationManager(this) }
    
    // Fee info update
    private var ccFeeClass: CCFeeClass? = null
    private val feeUpdateHandler = Handler(Looper.getMainLooper())
    
    // Auto-reply SMS tracking
    private val incomingCallTracker = mutableMapOf<String, Boolean>() // phoneNumber -> wasAnswered
    private val missedCallCounts = mutableMapOf<String, Int>() // phoneNumber -> missed call count
    private val autoReplySmsHandler = Handler(Looper.getMainLooper())

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            
            // Handle call recording based on state
            handleRecordingState(call, state)
            
            // Track incoming calls for auto-reply SMS
            if (!call.isOutgoing()) {
                val phoneNumber = call.details.handle?.schemeSpecificPart
                if (phoneNumber != null) {
                    when (state) {
                        Call.STATE_RINGING -> {
                            // Track incoming call
                            incomingCallTracker[phoneNumber] = false // Not answered yet
                        }
                        Call.STATE_ACTIVE -> {
                            // Call was answered
                            incomingCallTracker[phoneNumber] = true
                        }
                    }
                }
            }
            
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
                
                // Stop recording when call ends
                stopRecording()
                
                // Update fee info after call ends (with delay to ensure call is fully disconnected)
                if (state == Call.STATE_DISCONNECTED) {
                    updateFeeInfoAfterCall(call)
                    
                    // Check if auto-reply SMS should be sent for missed incoming calls
                    if (!call.isOutgoing()) {
                        handleMissedCallAutoReply(call)
                    }
                }
                
                // Check if auto redial should be triggered
                if (state == Call.STATE_DISCONNECTED && call.isOutgoing()) {
                    val disconnectCause = call.details.disconnectCause
                    val disconnectCode = disconnectCause?.code ?: DisconnectCause.UNKNOWN
                    if (CallManager.shouldAutoRedial(context, disconnectCode)) {
                        CallManager.startAutoRedial(context, disconnectCode)
                    } else {
                        CallManager.resetAutoRedialRetryCount()
                    }
                }
            } else {
                callNotificationManager.setupNotification()
                // Reset auto redial retry count when call is successfully connected
                if (state == Call.STATE_ACTIVE && call.isOutgoing()) {
                    CallManager.resetAutoRedialRetryCount()
                    CallManager.cancelAutoRedial()
                }
            }
            if (baseConfig.flashForAlerts) MyCameraImpl.newInstance(context).stopSOS()
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)
        
        // Store last dialed number for redial functionality
        if (call.isOutgoing()) {
            val handle = call.details.accountHandle
            val number = call.details.handle?.schemeSpecificPart
            if (number != null) {
                CallManager.setLastDialedNumber(number, handle)
                // Cancel any pending auto redial when a new call is initiated
                CallManager.cancelAutoRedial()
            }
        }

        // Incoming/Outgoing (locked): high priority (FSI)
        // Incoming (unlocked): if user opted in, low priority ➜ manual activity start, otherwise high priority (FSI)
        // Outgoing (unlocked): low priority ➜ manual activity start
        val isOutgoing = call.isOutgoing()
        val isIncoming = !isOutgoing
        val isDeviceLocked = !powerManager.isInteractive || keyguardManager.isDeviceLocked
        val lowPriority = when {
            isDeviceLocked -> false // High priority on locked screen
            isIncoming && !isDeviceLocked -> config.showIncomingCallsFullScreen
            else -> true
        }

        callNotificationManager.setupNotification(lowPriority)
        if (
            lowPriority
            || !hasPermission(PERMISSION_POST_NOTIFICATIONS)
            || !canUseFullScreenIntent()
        ) {
            try {
                val needSelectSIM = isOutgoing && call.details.accountHandle == null
                startActivity(CallActivity.getStartIntent(this, needSelectSIM = needSelectSIM))
            } catch (_: Exception) {
                // seems like startActivity can throw AndroidRuntimeException and
                // ActivityNotFoundException, not yet sure when and why, lets show a notification
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)
//        callNotificationManager.cancelNotification()
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        EventBus.getDefault().post(Events.RefreshCallLog)
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
            // Reset auto redial when all calls are removed
            CallManager.resetAutoRedialRetryCount()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }

        if (baseConfig.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
        }
    }

    override fun onSilenceRinger() {
        super.onSilenceRinger()
        if (baseConfig.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        callNotificationManager.cancelNotification()
        
        // Clean up fee info updater
        ccFeeClass?.unregisterReceiver()
        ccFeeClass = null
        
        if (baseConfig.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }
    
    /**
     * Update fee info after a call ends
     * Delays the update to ensure the call is fully disconnected before making USSD request
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun updateFeeInfoAfterCall(call: Call) {
        // Initialize CCFeeClass if not already initialized
        if (ccFeeClass == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ccFeeClass = CCFeeClass(context, registerReceiver = true)
            } catch (e: Exception) {
                Log.e("CallService", "Error initializing CCFeeClass", e)
                return
            }
        }
        
        if (ccFeeClass == null) {
            return
        }
        
        // Get the phone account handle from the call
        val phoneAccountHandle = call.details.accountHandle
        
        // Delay fee info update by 2 seconds to ensure call is fully disconnected
        // and network is ready for USSD request
        feeUpdateHandler.postDelayed({
            try {
                ccFeeClass?.updateFeeInfoAfterCall(phoneAccountHandle)
            } catch (e: Exception) {
                Log.e("CallService", "Error updating fee info after call", e)
            }
        }, 2000) // 2 second delay
    }
    
    /**
     * Handle call recording based on call state
     */
    private fun handleRecordingState(call: Call, state: Int) {
        if (!config.callRecordingEnabled) return
        
        when (state) {
            Call.STATE_ACTIVE -> {
                // Check if this call should be recorded based on auto-recording rule
                if (callRecorder == null) {
                    if (AutoRecordingHelper.shouldRecordCall(this, call)) {
                        startRecording(call)
                    } else {
                        Log.i("CallService", "Call does not match auto-recording rule, skipping")
                    }
                } else {
                    // Resume if was paused
                    callRecorder?.resumeRecording()
                }
            }
            Call.STATE_HOLDING -> {
                // Pause recording when call is on hold
                callRecorder?.markOnHold()
                callRecorder?.pauseRecording()
            }
            Call.STATE_DIALING, Call.STATE_RINGING -> {
                // Don't record during dialing/ringing
                // Recording will start when call becomes active
            }
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                // Stop recording when call ends
                stopRecording()
            }
        }
    }
    
    /**
     * Start recording the call
     */
    private fun startRecording(call: Call) {
        try {
            if (callRecorder != null) {
                Log.w("CallService", "Recorder already exists")
                return
            }
            
            // Create and start recorder
            val format = when (config.callRecordingFormat) {
                0 -> CallRecorder.OutputFormat.WAV
                1 -> CallRecorder.OutputFormat.OGG_OPUS
                2 -> CallRecorder.OutputFormat.M4A_AAC
                3 -> CallRecorder.OutputFormat.FLAC
                else -> CallRecorder.OutputFormat.WAV
            }
            
            callRecorder = CallRecorder(
                context = this,
                call = call,
                outputFormat = format,
                notificationManager = recordingNotificationManager
            )
            
            val success = callRecorder?.startRecording() ?: false
            if (success) {
                Log.i("CallService", "Call recording started")
                CallManager.updateRecordingState(true)
            } else {
                Log.e("CallService", "Failed to start call recording")
                callRecorder = null
                CallManager.updateRecordingState(false)
            }
        } catch (e: Exception) {
            Log.e("CallService", "Error starting recording", e)
            callRecorder = null
            CallManager.updateRecordingState(false)
        }
    }
    
    /**
     * Stop recording the call
     */
    private fun stopRecording() {
        callRecorder?.stopRecording()
        callRecorder = null
        CallManager.updateRecordingState(false)
    }

    /**
     * Toggle recording (for manual control)
     */
    fun toggleRecording() {
        val currentCall = CallManager.getPhoneState()
        
        if (callRecorder == null) {
            // Start recording
            when (currentCall) {
                is SingleCall -> {
                    if (currentCall.call.getStateCompat() == Call.STATE_ACTIVE) {
                        startRecording(currentCall.call)
                    }
                }
                is TwoCalls -> {
                    // Record primary call if available
                    currentCall.active.let { startRecording(it) }
                }
                else -> {
                    Log.w("CallService", "Cannot start recording: no active call")
                }
            }
        } else {
            // Stop recording
            stopRecording()
        }
    }
    
    /**
     * Handle auto-reply SMS for missed incoming calls
     */
    private fun handleMissedCallAutoReply(call: Call) {
        val phoneNumber = call.details.handle?.schemeSpecificPart ?: return
        val wasAnswered = incomingCallTracker[phoneNumber] ?: false
        
        // Remove from tracker
        incomingCallTracker.remove(phoneNumber)
        
        // Only send SMS if call was not answered (missed)
        if (!wasAnswered) {
            val disconnectCause = call.details.disconnectCause
            val disconnectCode = disconnectCause?.code ?: DisconnectCause.UNKNOWN
            
            // Check if this is a missed call (not rejected by user)
            // DisconnectCause.REJECTED means user explicitly rejected
            // DisconnectCause.MISSED indicates missed call
            val isMissedCall = when (disconnectCode) {
                DisconnectCause.MISSED -> true
                DisconnectCause.REJECTED -> false // User explicitly rejected, don't send SMS
                else -> {
                    // For other codes, check if call was never answered
                    !wasAnswered
                }
            }
            
            if (isMissedCall) {
                // Increment missed call count for this number
                val currentCount = missedCallCounts[phoneNumber] ?: 0
                val newCount = currentCount + 1
                missedCallCounts[phoneNumber] = newCount
                
                // Check if we've reached the threshold
                val threshold = context.config.autoReplySmsMissedCount
                if (newCount >= threshold) {
                    // Reset count after sending SMS
                    missedCallCounts.remove(phoneNumber)
                    
                    // Delay SMS sending slightly to ensure call is fully disconnected
                    autoReplySmsHandler.postDelayed({
                        sendAutoReplySms(phoneNumber, call.details.accountHandle)
                    }, 1000) // 1 second delay
                } else {
                    Log.d("CallService", "Missed call count for $phoneNumber: $newCount/$threshold")
                }
            }
        }
    }
    
    /**
     * Send auto-reply SMS for missed call
     */
    private fun sendAutoReplySms(phoneNumber: String, phoneAccountHandle: PhoneAccountHandle?) {
        try {
            val smsHelper = AutoReplySmsHelper(context)
            
            if (smsHelper.shouldSendAutoReply(phoneNumber)) {
                val message = context.config.autoReplySmsMessage
                val success = smsHelper.sendSms(phoneNumber, message, phoneAccountHandle)
                
                if (success) {
                    Log.i("CallService", "Auto-reply SMS sent to $phoneNumber")
                } else {
                    Log.w("CallService", "Failed to send auto-reply SMS to $phoneNumber")
                }
            }
        } catch (e: Exception) {
            Log.e("CallService", "Error sending auto-reply SMS", e)
        }
    }
}

