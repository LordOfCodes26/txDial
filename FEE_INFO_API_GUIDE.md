# Fee Information API Guide

This guide explains how other apps can access fee information from the Dialer app.

## Methods to Access Fee Information

### Method 1: ContentProvider (Recommended)

The most standard Android way to access fee information is through the ContentProvider.

#### 1. Add Permission to Your App's AndroidManifest.xml

```xml
<uses-permission android:name="com.android.dialer.permission.READ_FEE_INFO" />
```

#### 2. Query Fee Information

```kotlin
// Get fee info for a specific slot (0 or 1)
val uri = Uri.parse("content://com.android.dialer.feeinfo/fee_info/0")
val cursor = contentResolver.query(uri, null, null, null, null)

cursor?.use {
    if (it.moveToFirst()) {
        val slotId = it.getInt(it.getColumnIndex("slot_id"))
        val cash = it.getFloat(it.getColumnIndex("cash"))
        val minute = it.getInt(it.getColumnIndex("minute"))
        val remainMinute = it.getInt(it.getColumnIndex("remain_minute"))
        val sms = it.getInt(it.getColumnIndex("sms"))
        val byte = it.getInt(it.getColumnIndex("byte"))
        val finishDate = it.getString(it.getColumnIndex("finish_date"))
        
        // Use the fee information
        Log.d("FeeInfo", "Slot $slotId: Cash=$cash, Minutes=$minute")
    }
}

// Get fee info for all slots
val allUri = Uri.parse("content://com.android.dialer.feeinfo/fee_info")
val allCursor = contentResolver.query(allUri, null, null, null, null)

allCursor?.use {
    while (it.moveToNext()) {
        val slotId = it.getInt(it.getColumnIndex("slot_id"))
        val cash = it.getFloat(it.getColumnIndex("cash"))
        // Process each slot...
    }
}
```

#### Available Columns:
- `slot_id` (Int): SIM slot ID (0 or 1)
- `cash` (Float): Account balance
- `minute` (Int): Total minutes used
- `remain_minute` (Int): Remaining free minutes
- `sms` (Int): SMS count
- `byte` (Int): Data usage in bytes
- `finish_date` (String): Expiration date

### Method 2: Broadcast Receiver (Listen for Changes)

Listen for fee information updates:

```kotlin
class FeeInfoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.android.dialer.action.FEE_INFO_CHANGED") {
            // Fee information has changed
            // Query the ContentProvider to get updated values
            val uri = Uri.parse("content://com.android.dialer.feeinfo/fee_info")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            // Process updated fee info...
        }
    }
}

// Register the receiver
val filter = IntentFilter("com.android.dialer.action.FEE_INFO_CHANGED")
registerReceiver(FeeInfoReceiver(), filter)
```

### Method 3: Using FeeInfoHelper (If Available)

If you include the helper class in your project:

```kotlin
// Get fee info for slot 0
val feeInfo = FeeInfoHelper.getFeeInfo(context, 0)
if (feeInfo != null) {
    val cash = feeInfo.cash
    val minutes = feeInfo.minute
    val remainMinutes = feeInfo.remainMinute
    val sms = feeInfo.sms
    val bytes = feeInfo.byte
    val finishDate = feeInfo.finishDate
}

// Get all fee info
val allFeeInfo = FeeInfoHelper.getAllFeeInfo(context)
allFeeInfo.forEach { feeInfo ->
    Log.d("FeeInfo", "Slot ${feeInfo.slotId}: Cash=${feeInfo.cash}")
}

// Listen for changes
val receiver = FeeInfoHelper.registerFeeInfoListener(context) { slotId ->
    // Fee info changed - refresh your UI
    val updatedInfo = FeeInfoHelper.getFeeInfo(context, slotId ?: 0)
    // Update UI with new info
}

// Don't forget to unregister
FeeInfoHelper.unregisterFeeInfoListener(context, receiver)
```

## Java Example

```java
// Query fee info
Uri uri = Uri.parse("content://com.android.dialer.feeinfo/fee_info/0");
Cursor cursor = getContentResolver().query(uri, null, null, null, null);

if (cursor != null && cursor.moveToFirst()) {
    int slotId = cursor.getInt(cursor.getColumnIndex("slot_id"));
    float cash = cursor.getFloat(cursor.getColumnIndex("cash"));
    int minute = cursor.getInt(cursor.getColumnIndex("minute"));
    // Use the data...
    cursor.close();
}
```

## ContentProvider URIs

- All slots: `content://com.android.dialer.feeinfo/fee_info`
- Slot 0: `content://com.android.dialer.feeinfo/fee_info/0`
- Slot 1: `content://com.android.dialer.feeinfo/fee_info/1`

## Notes

- The ContentProvider is read-only (insert/update/delete operations are not supported)
- Fee information is updated automatically when USSD requests are made or SMS messages are received
- The broadcast `com.android.dialer.action.FEE_INFO_CHANGED` is sent whenever fee information changes
- Slot IDs: 0 = SIM 1, 1 = SIM 2

