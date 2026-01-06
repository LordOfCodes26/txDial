package com.android.dialer.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.android.dialer.providers.FeeInfoContentProvider

/**
 * Helper class for other apps to easily access fee information
 * 
 * Usage example:
 * 
 * // Get fee info for slot 0
 * val feeInfo = FeeInfoHelper.getFeeInfo(context, 0)
 * val cash = feeInfo.cash
 * val minutes = feeInfo.minute
 * 
 * // Listen for fee info changes
 * FeeInfoHelper.registerFeeInfoListener(context) { slotId ->
 *     // Fee info changed for slotId
 * }
 */
object FeeInfoHelper {
    private const val AUTHORITY = "com.android.dialer.feeinfo"
    private val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/fee_info")
    
    /**
     * Data class representing fee information for a SIM slot
     */
    data class FeeInfo(
        val slotId: Int,
        val cash: Float,
        val minute: Int,
        val remainMinute: Int,
        val sms: Int,
        val byte: Int,
        val finishDate: String
    )

    /**
     * Get fee information for a specific slot using ContentProvider
     */
    fun getFeeInfo(context: Context, slotId: Int): FeeInfo? {
        return try {
            val uri = Uri.withAppendedPath(CONTENT_URI, slotId.toString())
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    FeeInfo(
                        slotId = cursor.getInt(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_SLOT_ID)),
                        cash = cursor.getFloat(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_CASH)),
                        minute = cursor.getInt(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_MINUTE)),
                        remainMinute = cursor.getInt(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_REMAIN_MINUTE)),
                        sms = cursor.getInt(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_SMS)),
                        byte = cursor.getInt(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_BYTE)),
                        finishDate = cursor.getString(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_FINISH_DATE)) ?: ""
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FeeInfoHelper", "Error getting fee info", e)
            null
        }
    }

    /**
     * Get fee information for all slots
     */
    fun getAllFeeInfo(context: Context): List<FeeInfo> {
        val feeInfoList = mutableListOf<FeeInfo>()
        return try {
            context.contentResolver.query(CONTENT_URI, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    feeInfoList.add(
                        FeeInfo(
                            slotId = cursor.getInt(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_SLOT_ID)),
                            cash = cursor.getFloat(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_CASH)),
                            minute = cursor.getInt(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_MINUTE)),
                            remainMinute = cursor.getInt(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_REMAIN_MINUTE)),
                            sms = cursor.getInt(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_SMS)),
                            byte = cursor.getInt(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_BYTE)),
                            finishDate = cursor.getString(cursor.getColumnIndexOrThrow(FeeInfoContentProvider.COL_FINISH_DATE)) ?: ""
                        )
                    )
                }
            }
            feeInfoList
        } catch (e: Exception) {
            android.util.Log.e("FeeInfoHelper", "Error getting all fee info", e)
            feeInfoList
        }
    }

    /**
     * Register a listener for fee info changes via broadcast (Hybrid Method)
     * 
     * This method combines Broadcast Intent (for notifications) with ContentProvider (for data)
     * 
     * @param context The context
     * @param listener Callback when fee info changes. Receives:
     *   - slotId: The slot that changed (null if all slots or unknown)
     *   - quickInfo: Basic fee info from broadcast extras (for quick access)
     *   - fullInfo: Full fee info from ContentProvider (null if quickInfo is sufficient)
     * @return The registered BroadcastReceiver (keep reference to unregister later)
     */
    fun registerFeeInfoListener(
        context: Context,
        listener: (slotId: Int?, quickInfo: FeeInfo?, fullInfo: FeeInfo?) -> Unit
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) return
                
                // Get slot ID from broadcast
                val slotId = if (intent.hasExtra("slot_id")) {
                    intent.getIntExtra("slot_id", -1).takeIf { it >= 0 }
                } else {
                    null
                }
                
                // Try to get quick info from broadcast extras (if available)
                val quickInfo = if (intent.hasExtra("cash") && slotId != null) {
                    try {
                        FeeInfo(
                            slotId = slotId,
                            cash = intent.getFloatExtra("cash", -1f),
                            minute = intent.getIntExtra("minute", 0),
                            remainMinute = intent.getIntExtra("remain_minute", 0),
                            sms = intent.getIntExtra("sms", 0),
                            byte = intent.getIntExtra("byte", 0),
                            finishDate = intent.getStringExtra("finish_date") ?: ""
                        )
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                
                // Get full info from ContentProvider (always up-to-date)
                val fullInfo = if (slotId != null) {
                    getFeeInfo(context, slotId)
                } else {
                    // If slotId is null, get all info
                    getAllFeeInfo(context).firstOrNull()
                }
                
                // Call listener with both quick and full info
                listener(slotId, quickInfo, fullInfo)
            }
        }
        
        val filter = IntentFilter("com.android.dialer.action.FEE_INFO_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        
        return receiver
    }
    
    /**
     * Simplified listener that automatically fetches full info from ContentProvider
     * This is the recommended hybrid approach: Broadcast for notification + ContentProvider for data
     */
    fun registerFeeInfoListenerSimple(
        context: Context,
        listener: (slotId: Int?, feeInfo: FeeInfo?) -> Unit
    ): BroadcastReceiver {
        return registerFeeInfoListener(context) { slotId, quickInfo, fullInfo ->
            // Prefer full info from ContentProvider (always accurate)
            // Fall back to quick info if available
            val info = fullInfo ?: quickInfo
            listener(slotId, info)
        }
    }

    /**
     * Unregister fee info listener
     */
    fun unregisterFeeInfoListener(context: Context, receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            android.util.Log.e("FeeInfoHelper", "Error unregistering receiver", e)
        }
    }
}

