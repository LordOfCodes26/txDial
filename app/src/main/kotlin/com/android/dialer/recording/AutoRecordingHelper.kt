package com.android.dialer.recording

import android.content.Context
import android.provider.ContactsContract
import android.telecom.Call
import com.android.dialer.helpers.*
import com.android.dialer.extensions.config

/**
 * Helper class to determine if a call should be automatically recorded
 * based on the configured auto-recording rule.
 */
object AutoRecordingHelper {
    
    /**
     * Check if the given call should be automatically recorded based on the current rule.
     *
     * @param context Application context
     * @param call The call to check
     * @return true if the call should be recorded, false otherwise
     */
    fun shouldRecordCall(context: Context, call: Call): Boolean {
        val config = context.config
        
        // If recording is not enabled, don't record
        if (!config.callRecordingEnabled) {
            return false
        }
        
        // Get the auto-recording rule
        val rule = config.callRecordingAutoRule
        
        return when (rule) {
            RECORDING_RULE_NONE -> false // Manual only
            RECORDING_RULE_ALL_CALLS -> true // Record all calls
            RECORDING_RULE_UNKNOWN_NUMBERS -> shouldRecordUnknown(context, call)
            RECORDING_RULE_KNOWN_CONTACTS -> shouldRecordKnown(context, call)
            else -> true // Default to recording all calls
        }
    }
    
    /**
     * Check if call is from an unknown number (not in contacts)
     */
    private fun shouldRecordUnknown(context: Context, call: Call): Boolean {
        val phoneNumber = call.details?.handle?.schemeSpecificPart ?: return false
        return !isNumberInContacts(context, phoneNumber)
    }
    
    /**
     * Check if call is from a known contact (in contacts)
     */
    private fun shouldRecordKnown(context: Context, call: Call): Boolean {
        val phoneNumber = call.details?.handle?.schemeSpecificPart ?: return false
        return isNumberInContacts(context, phoneNumber)
    }
    
    /**
     * Check if a phone number exists in contacts
     *
     * @param context Application context
     * @param phoneNumber The phone number to check
     * @return true if the number is in contacts, false otherwise
     */
    private fun isNumberInContacts(context: Context, phoneNumber: String): Boolean {
        if (phoneNumber.isEmpty()) return false
        
        try {
            // Normalize the phone number for comparison
            val normalizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon()
                .appendPath(normalizedNumber)
                .build()
            
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                return cursor.count > 0
            }
        } catch (e: Exception) {
            // If there's an error, assume it's not in contacts
            return false
        }
        
        return false
    }
    
    /**
     * Get the name of the current auto-recording rule
     *
     * @param context Application context
     * @return The localized name of the current rule
     */
    fun getCurrentRuleName(context: Context): String {
        val config = context.config
        val rule = config.callRecordingAutoRule
        
        return when (rule) {
            RECORDING_RULE_NONE -> context.getString(com.android.dialer.R.string.recording_rule_none)
            RECORDING_RULE_ALL_CALLS -> context.getString(com.android.dialer.R.string.recording_rule_all)
            RECORDING_RULE_UNKNOWN_NUMBERS -> context.getString(com.android.dialer.R.string.recording_rule_unknown)
            RECORDING_RULE_KNOWN_CONTACTS -> context.getString(com.android.dialer.R.string.recording_rule_known)
            else -> context.getString(com.android.dialer.R.string.recording_rule_all)
        }
    }
}

