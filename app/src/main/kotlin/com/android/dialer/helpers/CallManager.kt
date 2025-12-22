package com.android.dialer.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import com.android.dialer.extensions.config
import com.android.dialer.extensions.getStateCompat
import com.android.dialer.extensions.hasCapability
import com.android.dialer.extensions.isConference
import com.android.dialer.models.AudioRoute
import com.goodwy.commons.extensions.telecomManager
import java.util.concurrent.CopyOnWriteArraySet

// inspired by https://github.com/Chooloo/call_manage
class CallManager {
    companion object {
        @SuppressLint("StaticFieldLeak")
        var inCallService: InCallService? = null
        private var call: Call? = null
        private val calls = mutableListOf<Call>()
        private val listeners = CopyOnWriteArraySet<CallManagerListener>()
        var isSpeakerOn: Boolean = false
        private var previousAudioRoute: Int = CallAudioState.ROUTE_WIRED_OR_EARPIECE
        
        // Call Recording
        var isRecording: Boolean = false
            private set
        
        // Redial and Auto Redial
        private var lastDialedNumber: String? = null
        private var lastDialedHandle: PhoneAccountHandle? = null
        private var autoRedialRetryCount: Int = 0
        private val autoRedialHandler = Handler(Looper.getMainLooper())
        private var autoRedialRunnable: Runnable? = null
        private var autoRedialActive: Boolean = true // Toggle state for auto redial (independent of config setting)

        fun onCallAdded(call: Call) {
            this.call = call
            calls.add(call)
            for (listener in listeners) {
                listener.onPrimaryCallChanged(call)
            }
            call.registerCallback(object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    updateState()
                }

                override fun onDetailsChanged(call: Call, details: Call.Details) {
                    updateState()
                }

                override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) {
                    updateState()
                }
            })
        }

        fun onCallRemoved(call: Call) {
            calls.remove(call)
            updateState()
        }

        fun onAudioStateChanged(audioState: CallAudioState) {
            val route = AudioRoute.fromRoute(audioState.route) ?: return
            for (listener in listeners) {
                listener.onAudioStateChanged(route)
            }
        }

        fun getPhoneState(): PhoneState {
            return when (calls.size) {
                0 -> NoCall
                1 -> SingleCall(calls.first())
                2 -> {
                    val active = calls.find { it.getStateCompat() == Call.STATE_ACTIVE }
                    val newCall = calls.find { it.getStateCompat() == Call.STATE_CONNECTING || it.getStateCompat() == Call.STATE_DIALING }
                    val onHold = calls.find { it.getStateCompat() == Call.STATE_HOLDING }
                    if (active != null && newCall != null) {
                        TwoCalls(newCall, active)
                    } else if (newCall != null && onHold != null) {
                        TwoCalls(newCall, onHold)
                    } else if (active != null && onHold != null) {
                        TwoCalls(active, onHold)
                    } else {
                        TwoCalls(calls[0], calls[1])
                    }
                }

                else -> {
                    val conference = calls.find { it.isConference() } ?: return NoCall
                    val secondCall = if (conference.children.size + 1 != calls.size) {
                        calls.filter { !it.isConference() }
                            .subtract(conference.children.toSet())
                            .firstOrNull()
                    } else {
                        null
                    }
                    if (secondCall == null) {
                        SingleCall(conference)
                    } else {
                        val newCallState = secondCall.getStateCompat()
                        if (newCallState == Call.STATE_ACTIVE || newCallState == Call.STATE_CONNECTING || newCallState == Call.STATE_DIALING) {
                            TwoCalls(secondCall, conference)
                        } else {
                            TwoCalls(conference, secondCall)
                        }
                    }
                }
            }
        }

        fun getPhoneSize(): Int {
            return calls.size
        }

        fun getCallAudioState() = inCallService?.callAudioState

        fun getSupportedAudioRoutes(): Array<AudioRoute> {
            return AudioRoute.entries.filter {
                val supportedRouteMask = getCallAudioState()?.supportedRouteMask
                if (supportedRouteMask != null) {
                    supportedRouteMask and it.route == it.route
                } else {
                    false
                }
            }.toTypedArray()
        }

        fun getCallAudioRoute() = AudioRoute.fromRoute(getCallAudioState()?.route)

        fun toggleSpeakerRoute(keepCalls: Boolean = false) {
            val currentAudioState = getCallAudioState() ?: return

            if (keepCalls) {
                if (currentAudioState.route == CallAudioState.ROUTE_EARPIECE) {
                    setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                    isSpeakerOn = true
                }
            } else {
                if (!isSpeakerOn) {
                    // We only remember the current route if it is not dynamic
                    if (currentAudioState.route != CallAudioState.ROUTE_SPEAKER) {
                        previousAudioRoute = currentAudioState.route
                    }
                    // Turn on the speaker - save the current route
                    setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                    isSpeakerOn = true
                } else {
                    // Turn off the speaker - return to the saved route
                    val targetRoute = getOptimalAudioRoute(previousAudioRoute, currentAudioState)
                    setAudioRoute(targetRoute)
                    isSpeakerOn = false
                }
            }

            // We hereby notify listeners of a change in status.
            notifyAudioStateChanged()
        }

        private fun getOptimalAudioRoute(preferredRoute: Int, audioState: CallAudioState): Int {
            val supportedRoutes = audioState.supportedRouteMask

            // First, let's try to return to the previous route
            if (supportedRoutes and preferredRoute != 0) {
                return preferredRoute
            }

            // If the previous one is unavailable, use the following priority: Bluetooth -> headset -> speaker
            return when {
                supportedRoutes and CallAudioState.ROUTE_BLUETOOTH != 0 -> CallAudioState.ROUTE_BLUETOOTH
                supportedRoutes and CallAudioState.ROUTE_WIRED_OR_EARPIECE != 0 -> CallAudioState.ROUTE_WIRED_OR_EARPIECE
                else -> CallAudioState.ROUTE_SPEAKER
            }
        }

        fun setAudioRoute(newRoute: Int) {
            inCallService?.setAudioRoute(newRoute)

            when (newRoute) {
                CallAudioState.ROUTE_SPEAKER -> isSpeakerOn = true
                else -> {
                    if (isSpeakerOn) {
                        isSpeakerOn = false
                        // Update the previous route if switching from speaker
                        if (newRoute != CallAudioState.ROUTE_SPEAKER) {
                            previousAudioRoute = newRoute
                        }
                    }
                }
            }

            notifyAudioStateChanged()
        }

        private fun notifyAudioStateChanged() {
            val route = getCallAudioRoute() ?: return
            for (listener in listeners) {
                listener.onAudioStateChanged(route)
            }
        }

        private fun updateState() {
            val primaryCall = when (val phoneState = getPhoneState()) {
                is NoCall -> null
                is SingleCall -> phoneState.call
                is TwoCalls -> phoneState.active
            }
            var notify = true
            if (primaryCall == null) {
                call = null
            } else if (primaryCall != call) {
                call = primaryCall
                for (listener in listeners) {
                    listener.onPrimaryCallChanged(primaryCall)
                }
                notify = false
            }
            if (notify) {
                for (listener in listeners) {
                    listener.onStateChanged()
                }
            }

            // remove all disconnected calls manually in case they are still here
            calls.removeAll { it.getStateCompat() == Call.STATE_DISCONNECTED }
        }

        fun getPrimaryCall(): Call? {
            return call
        }

        fun getConferenceCalls(): List<Call> {
            return calls.find { it.isConference() }?.children ?: emptyList()
        }

        fun accept() {
            call?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

        fun reject(rejectWithMessage: Boolean = false, textMessage: String? = null) {
            if (call != null) {
                val state = getState()
                if (state == Call.STATE_RINGING) {
                    call!!.reject(rejectWithMessage, textMessage)
                } else if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                    call!!.disconnect()
                }
            }
        }

        fun toggleHold(): Boolean {
            val isOnHold = getState() == Call.STATE_HOLDING
            if (isOnHold) {
                call?.unhold()
            } else {
                call?.hold()
            }
            return !isOnHold
        }

        fun swap() {
            if (calls.size > 1) {
                calls.find { it.getStateCompat() == Call.STATE_HOLDING }?.unhold()
            }
        }

        fun merge() {
            val conferenceableCalls = call!!.conferenceableCalls
            if (conferenceableCalls.isNotEmpty()) {
                call!!.conference(conferenceableCalls.first())
            } else {
                if (call!!.hasCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE)) {
                    call!!.mergeConference()
                }
            }
        }

        fun addListener(listener: CallManagerListener) {
            listeners.add(listener)
        }

        fun removeListener(listener: CallManagerListener) {
            listeners.remove(listener)
        }

        fun getState() = getPrimaryCall()?.getStateCompat()

        fun keypad(char: Char) {
            call?.playDtmfTone(char)
            Handler().postDelayed({
                call?.stopDtmfTone()
            }, DIALPAD_TONE_LENGTH_MS)
        }

        // Redial functionality
        fun setLastDialedNumber(number: String?, handle: PhoneAccountHandle? = null) {
            lastDialedNumber = number
            lastDialedHandle = handle
            // Reset auto redial retry count when a new number is dialed
            autoRedialRetryCount = 0
            autoRedialActive = true // Reset to active when a new call is made
        }

        fun getLastDialedNumber(): String? = lastDialedNumber

        fun redial(context: Context) {
            val number = lastDialedNumber ?: return
            // Cancel any pending auto redial when manually redialing
            cancelAutoRedial()
            redialNumber(context, number, lastDialedHandle)
        }

        @SuppressLint("MissingPermission")
        fun redialNumber(context: Context, number: String, handle: PhoneAccountHandle? = null) {
            try {
                val uri = Uri.fromParts("tel", number, null)
                val extras = android.os.Bundle().apply {
                    if (handle != null) {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    }
                    putBoolean(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, false)
                    putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
                }
                context.telecomManager.placeCall(uri, extras)
                setLastDialedNumber(number, handle)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Auto redial functionality
        fun shouldAutoRedial(context: Context, disconnectCode: Int): Boolean {
            val config = context.config
            if (!config.autoRedialEnabled) return false
            if (!autoRedialActive) return false // Check toggle state
            if (autoRedialRetryCount >= config.autoRedialMaxRetries) return false
            if (lastDialedNumber == null) return false

            // Auto redial for specific disconnect codes
            return when (disconnectCode) {
                DisconnectCause.BUSY,
                DisconnectCause.CANCELED,
                DisconnectCause.ERROR,
                DisconnectCause.REJECTED,
                DisconnectCause.UNKNOWN -> true
                else -> false
            }
        }
        
        fun setAutoRedialActive(active: Boolean) {
            autoRedialActive = active
            if (!active) {
                cancelAutoRedial() // Cancel any pending auto redial when disabled
            }
        }
        
        fun isAutoRedialActive(): Boolean = autoRedialActive

        fun startAutoRedial(context: Context, disconnectCode: Int) {
            if (!shouldAutoRedial(context, disconnectCode)) {
                cancelAutoRedial()
                return
            }

            val config = context.config
            cancelAutoRedial() // Cancel any existing auto redial

            autoRedialRetryCount++
            autoRedialRunnable = Runnable {
                if (lastDialedNumber != null) {
                    redialNumber(context, lastDialedNumber!!, lastDialedHandle)
                }
            }

            autoRedialHandler.postDelayed(autoRedialRunnable!!, config.autoRedialDelayMs)
        }

        fun cancelAutoRedial() {
            autoRedialRunnable?.let {
                autoRedialHandler.removeCallbacks(it)
                autoRedialRunnable = null
            }
        }

        fun resetAutoRedialRetryCount() {
            autoRedialRetryCount = 0
        }

        fun getAutoRedialRetryCount(): Int = autoRedialRetryCount

        // Call Recording
        fun toggleRecording(context: Context) {
            (inCallService as? com.android.dialer.services.CallService)?.toggleRecording()
        }

        fun updateRecordingState(recording: Boolean) {
            isRecording = recording
            updateState()
        }
    }
}

interface CallManagerListener {
    fun onStateChanged()
    fun onAudioStateChanged(audioState: AudioRoute)
    fun onPrimaryCallChanged(call: Call)
}

sealed class PhoneState
data object NoCall : PhoneState()
class SingleCall(val call: Call) : PhoneState()
class TwoCalls(val active: Call, val onHold: Call) : PhoneState()

