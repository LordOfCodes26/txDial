package com.android.dialer.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telecom.PhoneAccountHandle
import android.util.Log
import com.android.dialer.extensions.config
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_SEND_SMS

class AutoReplySmsHelper(private val context: Context) {
    companion object {
        private const val TAG = "AutoReplySmsHelper"
    }

    /**
     * Send SMS to a phone number
     * @param phoneNumber The recipient phone number
     * @param message The message to send
     * @param phoneAccountHandle Optional phone account handle for dual SIM devices
     * @return true if SMS was sent successfully, false otherwise
     */
    @SuppressLint("MissingPermission")
    fun sendSms(phoneNumber: String, message: String, phoneAccountHandle: PhoneAccountHandle? = null): Boolean {
        if (!context.hasPermission(PERMISSION_SEND_SMS)) {
            Log.w(TAG, "SEND_SMS permission not granted")
            return false
        }

        if (phoneNumber.isBlank()) {
            Log.w(TAG, "Phone number is blank")
            return false
        }

        if (message.isBlank()) {
            Log.w(TAG, "Message is blank")
            return false
        }

        try {
            val smsManager = if (phoneAccountHandle != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                // Get subscription ID from phone account handle for dual SIM
                val subscriptionId = getSubscriptionId(phoneAccountHandle)
                if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                } else {
                    SmsManager.getDefault()
                }
            } else {
                SmsManager.getDefault()
            }

            // Split message if it's too long (SMS limit is 160 characters for single part)
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }

            Log.i(TAG, "SMS sent successfully to $phoneNumber")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $phoneNumber", e)
            return false
        }
    }

    /**
     * Get subscription ID from phone account handle
     */
    private fun getSubscriptionId(phoneAccountHandle: PhoneAccountHandle): Int {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = context.getSystemService(android.content.Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                subscriptionManager?.activeSubscriptionInfoList?.firstOrNull { info ->
                    info.iccId == phoneAccountHandle.id
                }?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
            } else {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting subscription ID", e)
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }

    /**
     * Check if auto-reply SMS should be sent for a missed call
     */
    fun shouldSendAutoReply(phoneNumber: String?): Boolean {
        if (!context.config.autoReplySmsEnabled) {
            return false
        }

        if (phoneNumber.isNullOrBlank()) {
            return false
        }

        // Don't send to unknown/blocked numbers if desired
        // You can add additional filtering logic here if needed

        return true
    }
}

