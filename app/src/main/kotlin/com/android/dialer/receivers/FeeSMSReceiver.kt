package com.android.dialer.receivers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.dialer.R
import com.android.dialer.utils.CCFeeClass
import com.android.dialer.utils.SimCardUtils
import java.util.regex.Pattern

/**
 * BroadcastReceiver to parse SMS messages containing fee information
 * Based on the original SmsReceiver.java implementation
 */
class FeeSMSReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "FeeSMSReceiver"
        private const val SMS_NUMBER_919 = "919"
        
        // Reference to CCFeeClass instance
        @Volatile
        private var ccFeeClassInstance: CCFeeClass? = null
        
        fun setCCFeeClassInstance(instance: CCFeeClass?) {
            ccFeeClassInstance = instance
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") {
            return
        }

        try {
            val bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val resources = context.resources
            
            // Get subscription ID from intent (same as Java implementation)
            val subId = intent.getIntExtra("subscription", android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            
            for (pdu in pdus) {
                val smsMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(pdu as ByteArray, bundle.getString("format"))
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(pdu as ByteArray)
                }
                
                val sender = smsMessage?.originatingAddress ?: continue
                val messageBody = smsMessage?.messageBody ?: continue
                
                Log.d(TAG, "Received SMS from: $sender, Body: $messageBody")
                
                // Check if SMS is from 919 (exact match, not contains)
                if (sender != SMS_NUMBER_919) {
                    continue
                }
                
                // Check if message contains required patterns
                if (!messageBody.contains("분간의 무료통화") && !messageBody.contains("지고있습니다.")) {
                    continue
                }
                
                // Check if message contains received_cash_sms string
                val receivedCashSms = resources.getString(R.string.received_cash_sms)
                if (!messageBody.contains(receivedCashSms)) {
                    continue
                }
                
                Log.e(TAG, "Processing fee SMS: $messageBody")
                
                // Parse the SMS
                val nMinute = getKoryoMinute(messageBody, resources)
                val nMinuteMonth = getKoryoRemainMinute(messageBody, resources)
                val nSms = getKoryoSMS(messageBody, resources)
                val nBytes = 0 // getKoryoBytes(messageBody, resources) - commented out in original
                
                // Update fee info via CCFeeClass
                if (subId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    ccFeeClassInstance?.onReceived919SMS(nMinute, nMinuteMonth, nSms, nBytes, subId)
                } else {
                    Log.e(TAG, "Invalid subscription ID")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS", e)
        }
    }

    /**
     * Extract minutes from Koryo SMS using regex pattern
     */
    private fun getKoryoMinute(responseStr: String, resources: android.content.res.Resources): Int {
        val minuteFooter = resources.getString(R.string.minute_response_footer)
        val pattern = Pattern.compile("(\\d+)$minuteFooter")
        val matcher = pattern.matcher(responseStr)
        
        while (matcher.find()) {
            val group = matcher.group(1)
            if (group != null) {
                return group.toIntOrNull() ?: 0
            }
        }
        return 0
    }

    /**
     * Extract remaining minutes from Koryo SMS using regex pattern
     */
    private fun getKoryoRemainMinute(responseStr: String, resources: android.content.res.Resources): Int {
        val remainMinuteFooter = resources.getString(R.string.remain_minute_response_footer)
        val pattern = Pattern.compile("(\\d+)$remainMinuteFooter")
        val matcher = pattern.matcher(responseStr)
        
        while (matcher.find()) {
            val group = matcher.group(1)
            if (group != null) {
                return group.toIntOrNull() ?: 0
            }
        }
        return 0
    }

    /**
     * Extract SMS count from Koryo SMS using regex pattern
     */
    private fun getKoryoSMS(responseStr: String, resources: android.content.res.Resources): Int {
        val smsFooter = resources.getString(R.string.sms_response_footer)
        val pattern = Pattern.compile("(\\d+)$smsFooter")
        val matcher = pattern.matcher(responseStr)
        
        while (matcher.find()) {
            val group = matcher.group(1)
            if (group != null) {
                return group.toIntOrNull() ?: 0
            }
        }
        return 0
    }

    /**
     * Extract bytes from Koryo SMS using regex pattern
     * Note: This is commented out in the original implementation
     */
    private fun getKoryoBytes(responseStr: String, resources: android.content.res.Resources): Int {
        val bytesFooter = resources.getString(R.string.bytes_response_footer)
        val pattern = Pattern.compile("(\\d+)$bytesFooter")
        val matcher = pattern.matcher(responseStr)
        
        while (matcher.find()) {
            val group = matcher.group(1)
            if (group != null) {
                return group.toIntOrNull() ?: 0
            }
        }
        return 0
    }

    /**
     * Extract minutes from Kangsong SMS using regex pattern
     */
    private fun getKangSongMinute(responseStr: String, resources: android.content.res.Resources): Int {
        val minuteFooter = resources.getString(R.string.minute_response_footer)
        val pattern = Pattern.compile("(\\d+)$minuteFooter")
        val matcher = pattern.matcher(responseStr)
        
        while (matcher.find()) {
            val group = matcher.group(1)
            if (group != null) {
                return group.toIntOrNull() ?: 0
            }
        }
        return 0
    }

    /**
     * Extract remaining minutes from Kangsong SMS using regex pattern
     */
    private fun getKangSongRemainMinute(responseStr: String, resources: android.content.res.Resources): Int {
        val remainMinuteFooter = resources.getString(R.string.remain_minute_response_footer)
        val pattern = Pattern.compile("(\\d+)$remainMinuteFooter")
        val matcher = pattern.matcher(responseStr)
        
        while (matcher.find()) {
            val group = matcher.group(1)
            if (group != null) {
                return group.toIntOrNull() ?: 0
            }
        }
        return 0
    }

    /**
     * Extract SMS count from Kangsong SMS using regex pattern
     */
    private fun getKangSongSMS(responseStr: String, resources: android.content.res.Resources): Int {
        val smsFooter = resources.getString(R.string.sms_response_footer)
        val pattern = Pattern.compile("(\\d+)$smsFooter")
        val matcher = pattern.matcher(responseStr)
        
        while (matcher.find()) {
            val group = matcher.group(1)
            if (group != null) {
                return group.toIntOrNull() ?: 0
            }
        }
        return 0
    }

    /**
     * Extract bytes from Kangsong SMS using regex pattern
     */
    private fun getKangSongBytes(responseStr: String, resources: android.content.res.Resources): Int {
        val bytesFooter = resources.getString(R.string.bytes_response_footer)
        val pattern = Pattern.compile("(\\d+)$bytesFooter")
        val matcher = pattern.matcher(responseStr)
        
        while (matcher.find()) {
            val group = matcher.group(1)
            if (group != null) {
                return group.toIntOrNull() ?: 0
            }
        }
        return 0
    }
}
