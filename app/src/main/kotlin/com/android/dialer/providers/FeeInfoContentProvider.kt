package com.android.dialer.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.android.dialer.utils.FeeInfoUtils

/**
 * ContentProvider to expose fee information to other apps
 * 
 * Usage in other apps:
 * 
 * // Query fee info for slot 0
 * val uri = Uri.parse("content://com.android.dialer.feeinfo/fee_info/0")
 * val cursor = contentResolver.query(uri, null, null, null, null)
 * 
 * // Query all fee info
 * val uri = Uri.parse("content://com.android.dialer.feeinfo/fee_info")
 * val cursor = contentResolver.query(uri, null, null, null, null)
 */
class FeeInfoContentProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY = "com.android.dialer.feeinfo"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/fee_info")
        
        private const val FEE_INFO = 1
        private const val FEE_INFO_SLOT = 2
        
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "fee_info", FEE_INFO)
            addURI(AUTHORITY, "fee_info/#", FEE_INFO_SLOT)
        }
        
        // Column names
        const val COL_SLOT_ID = "slot_id"
        const val COL_CASH = "cash"
        const val COL_MINUTE = "minute"
        const val COL_REMAIN_MINUTE = "remain_minute"
        const val COL_SMS = "sms"
        const val COL_BYTE = "byte"
        const val COL_FINISH_DATE = "finish_date"
        
        // Column array for queries
        private val COLUMNS = arrayOf(
            COL_SLOT_ID,
            COL_CASH,
            COL_MINUTE,
            COL_REMAIN_MINUTE,
            COL_SMS,
            COL_BYTE,
            COL_FINISH_DATE
        )
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        
        val match = uriMatcher.match(uri)
        val cursor = MatrixCursor(projection ?: COLUMNS)
        
        when (match) {
            FEE_INFO -> {
                // Return fee info for all slots (0 and 1)
                for (slotId in 0..1) {
                    addFeeInfoRow(cursor, context, slotId, projection)
                }
            }
            FEE_INFO_SLOT -> {
                // Return fee info for specific slot
                val slotId = uri.lastPathSegment?.toIntOrNull() ?: return null
                addFeeInfoRow(cursor, context, slotId, projection)
            }
            else -> return null
        }
        
        return cursor
    }

    private fun addFeeInfoRow(
        cursor: MatrixCursor,
        context: android.content.Context,
        slotId: Int,
        projection: Array<String>?
    ) {
        val row = mutableListOf<Any?>()
        val columns = projection ?: COLUMNS
        
        for (column in columns) {
            when (column) {
                COL_SLOT_ID -> row.add(slotId)
                COL_CASH -> row.add(FeeInfoUtils.getCash(context, slotId))
                COL_MINUTE -> row.add(FeeInfoUtils.getMinute(context, slotId))
                COL_REMAIN_MINUTE -> row.add(FeeInfoUtils.getRemainMinute(context, slotId))
                COL_SMS -> row.add(FeeInfoUtils.getSms(context, slotId))
                COL_BYTE -> row.add(FeeInfoUtils.getByte(context, slotId))
                COL_FINISH_DATE -> row.add(FeeInfoUtils.getFinishDate(context, slotId))
                else -> row.add(null)
            }
        }
        
        cursor.addRow(row.toTypedArray())
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            FEE_INFO -> "vnd.android.cursor.dir/vnd.com.android.dialer.feeinfo"
            FEE_INFO_SLOT -> "vnd.android.cursor.item/vnd.com.android.dialer.feeinfo"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // Read-only provider
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        // Read-only provider
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        // Read-only provider
        return 0
    }
}

