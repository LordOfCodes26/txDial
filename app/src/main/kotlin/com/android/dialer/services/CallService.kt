package com.android.dialer.services

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.canUseFullScreenIntent
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import com.android.dialer.activities.CallActivity
import com.android.dialer.extensions.config
import com.android.dialer.extensions.isOutgoing
import com.android.dialer.extensions.keyguardManager
import com.android.dialer.extensions.powerManager
import com.android.dialer.helpers.*
import com.android.dialer.models.Events
import org.greenrobot.eventbus.EventBus

class CallService : InCallService() {
    private val context = this
    private val callNotificationManager by lazy { CallNotificationManager(this) }

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
                
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
        callNotificationManager.cancelNotification()
        if (baseConfig.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }
}

