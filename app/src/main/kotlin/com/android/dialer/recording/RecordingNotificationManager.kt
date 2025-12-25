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
     * BCR-style: Low importance, silent, no badge
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // BCR uses low importance
            ).apply {
                description = "Notifications shown during call recording"
                setShowBadge(false) // No badge
                enableLights(false) // No LED
                enableVibration(false) // No vibration
                setSound(null, null) // Silent
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC // Show on lock screen
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Show recording notification (BCR-style)
     * @param phoneNumber Phone number being recorded
     * @param contactName Contact name if available (optional)
     * @param isPaused Whether recording is paused
     */
    fun showRecordingNotification(phoneNumber: String?, contactName: String? = null, isPaused: Boolean = false) {
        val notification = buildRecordingNotification(phoneNumber, contactName, isPaused)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Update notification when recording state changes
     */
    fun updateNotification(phoneNumber: String?, contactName: String? = null, isPaused: Boolean) {
        showRecordingNotification(phoneNumber, contactName, isPaused)
    }
    
    /**
     * Cancel/hide the recording notification
     */
    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
    
    /**
     * Build the recording notification (BCR-style)
     */
    private fun buildRecordingNotification(phoneNumber: String?, contactName: String? = null, isPaused: Boolean): Notification {
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
        
        // BCR-style: Simple title "Recording" or "Recording paused"
        val title = if (isPaused) {
            context.getString(R.string.recording_paused)
        } else {
            context.getString(R.string.recording)
        }
        
        // BCR-style: Show contact name if available, otherwise phone number
        val text = when {
            !contactName.isNullOrEmpty() -> contactName // BCR shows contact name first
            !phoneNumber.isNullOrEmpty() -> formatPhoneNumber(phoneNumber) // Then formatted phone number
            else -> context.getString(R.string.recording_in_progress) // Fallback
        }
        
        // Build notification in BCR style
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_microphone_vector)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true) // Persistent notification
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(context.getColor(R.color.color_primary))
            .setShowWhen(false) // Don't show timestamp, we use chronometer instead
        
        // Add chronometer (timer) - BCR style
        if (!isPaused) {
            // Show elapsed time using chronometer
            builder.setUsesChronometer(true)
            builder.setWhen(System.currentTimeMillis())
        } else {
            // When paused, show "On Hold" as subtext
            builder.setSubText(context.getString(R.string.call_on_hold))
            builder.setUsesChronometer(false)
        }
        
        // Android 12+ foreground service behavior
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        
        return builder.build()
    }
    
    /**
     * Format phone number for display (BCR-style: simple and clean)
     */
    private fun formatPhoneNumber(number: String): String {
        // BCR shows numbers simply, just clean up formatting
        val cleaned = number.replace(Regex("[^+\\d]"), "") // Keep only digits and +
        
        // For US numbers, format nicely: +1 (234) 567-8900
        return if (cleaned.startsWith("+1") && cleaned.length == 12) {
            val area = cleaned.substring(2, 5)
            val first = cleaned.substring(5, 8)
            val second = cleaned.substring(8, 12)
            "+1 ($area) $first-$second"
        } else if (cleaned.length == 10 && !cleaned.startsWith("+")) {
            // 10-digit US number without country code
            val area = cleaned.substring(0, 3)
            val first = cleaned.substring(3, 6)
            val second = cleaned.substring(6, 10)
            "($area) $first-$second"
        } else {
            // For other numbers, just return cleaned version
            cleaned
        }
    }
    
    /**
     * Get notification for foreground service
     * Used when recording as a foreground service
     */
    fun getRecordingNotification(phoneNumber: String?, contactName: String? = null): Notification {
        return buildRecordingNotification(phoneNumber, contactName, false)
    }
}

