package com.android.dialer.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.android.dialer.R
import com.android.dialer.activities.MainActivity

/**
 * Manages notifications for call recording
 * Shows a persistent notification while recording is active
 */
class RecordingNotificationManager(private val context: Context) {
    
    companion object {
        private const val CHANNEL_ID = "call_recording"
        private const val CHANNEL_NAME = "Call Recording"
        private const val NOTIFICATION_ID = 1001
        
        // Intent action for stopping recording
        const val ACTION_STOP_RECORDING = "com.android.dialer.STOP_RECORDING"
    }
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createNotificationChannel()
    }
    
    /**
     * Create notification channel for recording notifications (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications shown during call recording"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Show recording notification
     */
    fun showRecordingNotification(phoneNumber: String?, isPaused: Boolean = false) {
        val notification = buildRecordingNotification(phoneNumber, isPaused)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Update notification when recording state changes
     */
    fun updateNotification(phoneNumber: String?, isPaused: Boolean) {
        showRecordingNotification(phoneNumber, isPaused)
    }
    
    /**
     * Cancel/hide the recording notification
     */
    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
    
    /**
     * Build the recording notification
     */
    private fun buildRecordingNotification(phoneNumber: String?, isPaused: Boolean): Notification {
        // Main intent - open app when notification is tapped
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Format title and text
        val title = if (isPaused) {
            context.getString(R.string.recording_paused)
        } else {
            context.getString(R.string.recording_call)
        }
        
        val text = if (phoneNumber.isNullOrEmpty()) {
            context.getString(R.string.recording_in_progress)
        } else {
            context.getString(R.string.recording_phone_number, formatPhoneNumber(phoneNumber))
        }
        
        // Build notification
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_microphone_vector) // Recording icon
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true) // Can't be dismissed while recording
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent)
            .setShowWhen(true)
            .setUsesChronometer(!isPaused) // Show timer when recording
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(context.getColor(R.color.color_primary))
            .apply {
                // Add recording indicator for Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
                
                // Show "On Hold" status if paused
                if (isPaused) {
                    setSubText(context.getString(R.string.call_on_hold))
                }
            }
            .build()
    }
    
    /**
     * Format phone number for display
     */
    private fun formatPhoneNumber(number: String): String {
        return if (number.length > 10) {
            // Format long numbers
            number.replace(Regex("(\\d{3})(\\d{3})(\\d{4})"), "$1-$2-$3")
        } else {
            number
        }
    }
    
    /**
     * Get notification for foreground service
     * Used when recording as a foreground service
     */
    fun getRecordingNotification(phoneNumber: String?): Notification {
        return buildRecordingNotification(phoneNumber, false)
    }
}

