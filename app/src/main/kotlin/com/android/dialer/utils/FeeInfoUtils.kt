package com.android.dialer.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Utility class for managing fee information (cash, minutes, SMS, etc.)
 */
object FeeInfoUtils {
    private const val PREFS_NAME = "fee_info_prefs"
    private const val KEY_CASH_PREFIX = "cash_slot_"
    private const val KEY_MINUTE_PREFIX = "minute_slot_"
    private const val KEY_REMAIN_MINUTE_PREFIX = "remain_minute_slot_"
    private const val KEY_SMS_PREFIX = "sms_slot_"
    private const val KEY_BYTE_PREFIX = "byte_slot_"
    private const val KEY_FINISH_DATE_PREFIX = "finish_date_slot_"
    private const val ACTION_FEE_INFO_CHANGED = "com.android.dialer.action.FEE_INFO_CHANGED"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setCash(context: Context, slotId: Int, cash: Float) {
        getPrefs(context).edit {
            putFloat("$KEY_CASH_PREFIX$slotId", cash)
        }
    }

    fun getCash(context: Context, slotId: Int): Float {
        return getPrefs(context).getFloat("$KEY_CASH_PREFIX$slotId", -1f)
    }

    fun setMinute(context: Context, slotId: Int, minute: Int) {
        getPrefs(context).edit {
            putInt("$KEY_MINUTE_PREFIX$slotId", minute)
        }
    }

    fun getMinute(context: Context, slotId: Int): Int {
        return getPrefs(context).getInt("$KEY_MINUTE_PREFIX$slotId", 0)
    }

    fun setRemainMinute(context: Context, slotId: Int, remainMinute: Int) {
        getPrefs(context).edit {
            putInt("$KEY_REMAIN_MINUTE_PREFIX$slotId", remainMinute)
        }
    }

    fun getRemainMinute(context: Context, slotId: Int): Int {
        return getPrefs(context).getInt("$KEY_REMAIN_MINUTE_PREFIX$slotId", 0)
    }

    fun setSms(context: Context, slotId: Int, sms: Int) {
        getPrefs(context).edit {
            putInt("$KEY_SMS_PREFIX$slotId", sms)
        }
    }

    fun getSms(context: Context, slotId: Int): Int {
        return getPrefs(context).getInt("$KEY_SMS_PREFIX$slotId", 0)
    }

    fun setByte(context: Context, slotId: Int, bytes: Int) {
        getPrefs(context).edit {
            putInt("$KEY_BYTE_PREFIX$slotId", bytes)
        }
    }

    fun getByte(context: Context, slotId: Int): Int {
        return getPrefs(context).getInt("$KEY_BYTE_PREFIX$slotId", 0)
    }

    fun setFinishDate(context: Context, slotId: Int, date: String) {
        getPrefs(context).edit {
            putString("$KEY_FINISH_DATE_PREFIX$slotId", date)
        }
    }

    fun getFinishDate(context: Context, slotId: Int): String {
        return getPrefs(context).getString("$KEY_FINISH_DATE_PREFIX$slotId", "") ?: ""
    }

    /**
     * Send broadcast when fee info changes (hybrid method)
     * Includes slot ID and basic info in intent extras for quick access
     * Apps can use ContentProvider for full details
     */
    fun sendFeeInfoChange(context: Context, slotId: Int? = null) {
        val intent = Intent(ACTION_FEE_INFO_CHANGED).apply {
            // Include slot ID if specified
            if (slotId != null) {
                putExtra("slot_id", slotId)
                // Include basic fee info in broadcast for quick access
                putExtra("cash", getCash(context, slotId))
                putExtra("minute", getMinute(context, slotId))
                putExtra("remain_minute", getRemainMinute(context, slotId))
                putExtra("sms", getSms(context, slotId))
                putExtra("byte", getByte(context, slotId))
                putExtra("finish_date", getFinishDate(context, slotId))
            }
        }
        context.sendBroadcast(intent)
    }
    
    /**
     * Send broadcast for all slots (when multiple slots are updated)
     */
    fun sendFeeInfoChangeAll(context: Context) {
        sendFeeInfoChange(context, null)
    }
}

