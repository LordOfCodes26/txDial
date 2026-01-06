# Hybrid Fee Information API Guide

This guide explains how to use the **hybrid method** that combines **ContentProvider** and **Broadcast Intent** for accessing fee information.

## Why Hybrid Method?

- **Broadcast Intent**: Fast notification when fee info changes (event-driven)
- **ContentProvider**: On-demand queries for current data (always up-to-date)
- **Best of Both**: Get notified instantly, then query for full details

## Implementation Overview

The hybrid method works in two ways:

1. **Broadcast Intent** includes basic fee info in extras (for quick access)
2. **ContentProvider** provides full, structured data (for detailed queries)

## Usage in Other Apps

### Method 1: Simple Hybrid Listener (Recommended)

```kotlin
// Register listener that automatically handles both broadcast and ContentProvider
val receiver = FeeInfoHelper.registerFeeInfoListenerSimple(context) { slotId, feeInfo ->
    // slotId: Which SIM slot changed (0 or 1, null if unknown)
    // feeInfo: Full fee information from ContentProvider (always accurate)
    
    if (feeInfo != null) {
        updateUI(
            cash = feeInfo.cash,
            minutes = feeInfo.minute,
            remainMinutes = feeInfo.remainMinute,
            sms = feeInfo.sms,
            dataBytes = feeInfo.byte,
            expireDate = feeInfo.finishDate
        )
    }
}

// Don't forget to unregister
FeeInfoHelper.unregisterFeeInfoListener(context, receiver)
```

### Method 2: Advanced Hybrid Listener

```kotlin
// Get both quick info from broadcast and full info from ContentProvider
val receiver = FeeInfoHelper.registerFeeInfoListener(context) { slotId, quickInfo, fullInfo ->
    // slotId: Which SIM slot changed
    // quickInfo: Basic info from broadcast extras (may be null)
    // fullInfo: Complete info from ContentProvider (recommended to use this)
    
    // Prefer fullInfo (always accurate and complete)
    val info = fullInfo ?: quickInfo
    
    if (info != null) {
        Log.d("FeeInfo", "Slot ${info.slotId} updated: Cash=${info.cash}")
        // Update your UI
    }
}

// Unregister when done
FeeInfoHelper.unregisterFeeInfoListener(context, receiver)
```

### Method 3: Query On-Demand (ContentProvider Only)

```kotlin
// Get current fee info anytime (no listener needed)
val feeInfo = FeeInfoHelper.getFeeInfo(context, 0) // Slot 0
if (feeInfo != null) {
    val cash = feeInfo.cash
    val minutes = feeInfo.minute
    // Use the data...
}

// Get all slots
val allFeeInfo = FeeInfoHelper.getAllFeeInfo(context)
allFeeInfo.forEach { info ->
    Log.d("FeeInfo", "Slot ${info.slotId}: Cash=${info.cash}")
}
```

### Method 4: Manual Broadcast Listener + ContentProvider Query

```kotlin
// Register broadcast receiver manually
val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.android.dialer.action.FEE_INFO_CHANGED") {
            // Get slot ID from broadcast
            val slotId = intent.getIntExtra("slot_id", -1).takeIf { it >= 0 }
            
            // Option 1: Use quick info from broadcast extras
            if (intent.hasExtra("cash")) {
                val cash = intent.getFloatExtra("cash", -1f)
                val minutes = intent.getIntExtra("minute", 0)
                // Quick update with broadcast data
            }
            
            // Option 2: Query ContentProvider for full, accurate data (recommended)
            val fullInfo = if (slotId != null) {
                FeeInfoHelper.getFeeInfo(context, slotId)
            } else {
                FeeInfoHelper.getAllFeeInfo(context).firstOrNull()
            }
            
            // Update UI with full info
            fullInfo?.let { updateUI(it) }
        }
    }
}

val filter = IntentFilter("com.android.dialer.action.FEE_INFO_CHANGED")
registerReceiver(receiver, filter)
```

## Broadcast Intent Extras

When fee info changes, the broadcast includes:

- `slot_id` (Int): Which SIM slot changed (0 or 1)
- `cash` (Float): Account balance
- `minute` (Int): Total minutes used
- `remain_minute` (Int): Remaining free minutes
- `sms` (Int): SMS count
- `byte` (Int): Data usage in bytes
- `finish_date` (String): Expiration date

## Complete Example

```kotlin
class MyActivity : AppCompatActivity() {
    private var feeInfoReceiver: BroadcastReceiver? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Register hybrid listener
        feeInfoReceiver = FeeInfoHelper.registerFeeInfoListenerSimple(this) { slotId, feeInfo ->
            // Update UI when fee info changes
            feeInfo?.let {
                updateFeeDisplay(it)
            }
        }
        
        // Also get current fee info on startup
        val currentFeeInfo = FeeInfoHelper.getFeeInfo(this, 0)
        currentFeeInfo?.let { updateFeeDisplay(it) }
    }
    
    private fun updateFeeDisplay(feeInfo: FeeInfoHelper.FeeInfo) {
        // Update your UI with fee information
        cashTextView.text = "Balance: ${feeInfo.cash}원"
        minutesTextView.text = "Minutes: ${feeInfo.minute}"
        // etc...
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Unregister receiver
        feeInfoReceiver?.let {
            FeeInfoHelper.unregisterFeeInfoListener(this, it)
        }
    }
}
```

## Benefits of Hybrid Method

1. **Fast Notifications**: Broadcast provides instant notification when data changes
2. **Accurate Data**: ContentProvider always returns the latest, complete data
3. **Flexible**: Can use broadcast for quick updates, ContentProvider for detailed queries
4. **Efficient**: Broadcast includes basic info for quick access, ContentProvider for full details
5. **Reliable**: If broadcast is missed, can always query ContentProvider

## Best Practices

1. **Use Broadcast for Notifications**: Listen for changes via broadcast
2. **Use ContentProvider for Data**: Always query ContentProvider for actual data
3. **Handle Both**: Use quick info from broadcast for immediate UI updates, then refresh with ContentProvider
4. **Query on Startup**: Always query ContentProvider when your app starts to get current state
5. **Unregister Properly**: Always unregister receivers to prevent memory leaks

## Permission Required

Add to your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="com.android.dialer.permission.READ_FEE_INFO" />
```

