package com.android.dialer.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.android.dialer.activities.CallActivity
import com.android.dialer.extensions.audioManager
import com.android.dialer.extensions.config
import com.android.dialer.helpers.ACCEPT_CALL
import com.android.dialer.helpers.CallManager
import com.android.dialer.helpers.CallNotificationManager
import com.android.dialer.helpers.DECLINE_CALL
import com.android.dialer.helpers.MICROPHONE_CALL
import com.android.dialer.helpers.SPEAKER_CALL

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DECLINE_CALL -> CallManager.reject()
            ACCEPT_CALL -> {
                if (!context.config.keepCallsInPopUp) context.startActivity(CallActivity.getStartIntent(context))
                CallManager.accept()
                if (context.config.keepCallsInPopUp) CallManager.toggleSpeakerRoute(true)
            }

            MICROPHONE_CALL -> {
//                val isMicrophoneMute = context.audioManager.isMicrophoneMute
//                CallManager.inCallService?.setMuted(!isMicrophoneMute)

                val audioManager = context.audioManager
                audioManager.isMicrophoneMute = !audioManager.isMicrophoneMute
                CallNotificationManager(context).updateNotification()
            }

            SPEAKER_CALL -> {
//                val callManager = CallManager
//                val currentRoute = callManager.getCallAudioRoute()
//                val newRoute = if (currentRoute == AudioRoute.SPEAKER) {
//                    CallAudioState.ROUTE_WIRED_OR_EARPIECE
//                } else {
//                    CallAudioState.ROUTE_SPEAKER
//                }
//                callManager.setAudioRoute(newRoute)
//                CallNotificationManager(context).updateNotification()

                CallManager.toggleSpeakerRoute()
                CallNotificationManager(context).updateNotification()
            }
        }
    }
}
