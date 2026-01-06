package com.android.dialer.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/**
 * Utility class for SIM card operations
 */
object SimCardUtils {
    const val KORYO_NET = 5  // MNC for Koryo network
    const val KANGSONG_NET = 6  // MNC for Kangsong network
    const val MIRAE_NET = 3  // MNC for Mirae network

    /**
     * Check if dual SIM is supported and get SIM information
     */
    @SuppressLint("MissingPermission")
    fun dualSimSupported(context: Context, listener: ActionFinishListener? = null) {
        try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptionInfoList = subManager.activeSubscriptionInfoList
            if (subscriptionInfoList != null && subscriptionInfoList.isNotEmpty()) {
                // Process SIM information
                listener?.onFinished()
            } else {
                listener?.onFinished()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            listener?.onFinished()
        }
    }

    /**
     * Get SIM slot ID from subscription ID
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun getSlotIdUsingSubId(subId: Int): Int {
        return try {
            SubscriptionManager.getSlotIndex(subId)
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Get subscription info for a specific slot
     */
    @SuppressLint("MissingPermission")
    fun getSubscriptionInfoForSlot(context: Context, slotId: Int): SubscriptionInfo? {
        return try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptionInfoList = subManager.activeSubscriptionInfoList
            subscriptionInfoList?.firstOrNull { subscriptionInfo ->
                subscriptionInfo.simSlotIndex == slotId
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if a SIM card is Koryo network
     */
    fun isKoryoNetwork(mnc: Int): Boolean {
        return mnc == KORYO_NET
    }

    /**
     * Check if a SIM card is Kangsong network
     */
    fun isKangsongNetwork(mnc: Int): Boolean {
        return mnc == KANGSONG_NET
    }

    /**
     * Check if a SIM card is Mirae network
     */
    fun isMiraeNetwork(mnc: Int): Boolean {
        return mnc == MIRAE_NET
    }
}

