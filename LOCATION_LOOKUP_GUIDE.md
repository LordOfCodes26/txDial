# Phone Number Location Lookup Guide

This guide explains how to get the city and district location from a phone number.

## Overview

The system provides two functions to retrieve location information from a phone number:

1. **`getLocationByPrefixAsync()`** - Asynchronous (recommended for UI threads)
2. **`getLocationByPrefix()`** - Synchronous (must be called from background thread)

Both functions:
- Match the phone number to a format definition
- Extract the prefix and district code based on the matched format
- Look up the city from `phone_prefix_locations` table
- Look up the district/area from `phone_districts` table
- Return a formatted string: `"City"` or `"City - District"`

## Function 1: Asynchronous (Recommended)

Use this when calling from the main/UI thread (Activities, Fragments, etc.)

### Syntax
```kotlin
fun String.getLocationByPrefixAsync(context: Context, callback: (String?) -> Unit)
```

### Parameters
- `context`: Android Context (Activity, Fragment, Application, etc.)
- `callback`: Lambda function that receives the location string or `null`

### Return Value
- `String?`: Location string in format `"City"` or `"City - District"`, or `null` if not found

### Example Usage

```kotlin
// In an Activity or Fragment
val phoneNumber = "0211234567"

phoneNumber.getLocationByPrefixAsync(this) { location ->
    if (location != null) {
        // Display the location
        textView.text = location  // e.g., " - "
    } else {
        // Location not found
        textView.text = "Unknown location"
    }
}
```

### Real Example from CallActivity

```kotlin
// From CallActivity.kt - showing location on call screen
number.getLocationByPrefixAsync(this@CallActivity) { location ->
    if (location != null && callContact != null && callContact!!.number == number) {
        callerDescription.text = formatterUnicodeWrap(location)
        callerDescription.beVisible()
    }
}
```

## Function 2: Synchronous

Use this when you're already on a background thread (coroutines, background tasks, etc.)

### Syntax
```kotlin
fun String.getLocationByPrefix(context: Context): String?
```

### Parameters
- `context`: Android Context

### Return Value
- `String?`: Location string in format `"City"` or `"City - District"`, or `null` if not found

### Example Usage

```kotlin
// In a background thread (coroutine, background task, etc.)
import com.goodwy.commons.helpers.ensureBackgroundThread

val phoneNumber = "0211234567"

ensureBackgroundThread {
    val location = phoneNumber.getLocationByPrefix(context)
    if (location != null) {
        // Use the location
        println("Location: $location")  // e.g., " - "
    }
}
```

### Example with Coroutines

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun getLocation(phoneNumber: String, context: Context): String? {
    return withContext(Dispatchers.IO) {
        phoneNumber.getLocationByPrefix(context)
    }
}

// Usage
lifecycleScope.launch {
    val location = getLocation("0211234567", this@Activity)
    textView.text = location ?: "Unknown"
}
```

## How It Works

1. **Format Matching**: The system matches the phone number to a format definition from `phone_number_formats.json`
   - Example: `0211234567` matches format `021-XX-XXXX`
   - Extracts prefix: `"021"` (3 digits)
   - Extracts district code: `"12"` (2 digits)

2. **Database Lookup**:
   - City: Queries `phone_prefix_locations` table with prefix `"021"`
   - District: Queries `phone_districts` table with prefix `"021"` and district code `"12"`

3. **Result Formatting**:
   - If both city and district found: `"City - District"` (e.g., `" - "`)
   - If only city found: `"City"` (e.g., `""`)
   - If nothing found: `null`

## Examples with Different Formats

### Example 1: 2-digit prefix (01-7XX-XXXX)
```kotlin
val number = "0171234567"
number.getLocationByPrefixAsync(this) { location ->
    // location = " - " (if prefix 01 = , district 712 = )
}
```

### Example 2: 3-digit prefix (021-XX-XXXX)
```kotlin
val number = "0211234567"
number.getLocationByPrefixAsync(this) { location ->
    // location = " - District12" (if prefix 021 = , district 12 = District12)
}
```

### Example 3: 4-digit prefix (0219-X-XXXX)
```kotlin
val number = "0219123456"
number.getLocationByPrefixAsync(this) { location ->
    // location = "City - Area1" (if prefix 0219 = City, district 1 = Area1)
}
```

### Example 4: Special format (1309-X-XXXX)
```kotlin
val number = "1309123456"
number.getLocationByPrefixAsync(this) { location ->
    // location = "City - District09" (if prefix 13 = City, district 09 = District09)
}
```

## Important Notes

1. **Thread Safety**:
   - `getLocationByPrefixAsync()` can be called from any thread (it handles threading internally)
   - `getLocationByPrefix()` MUST be called from a background thread

2. **Database Access**:
   - Both functions access the Room database
   - The async version automatically handles background threading
   - The sync version requires you to ensure you're on a background thread

3. **Return Value**:
   - Returns `null` if:
     - Phone number doesn't match any format
     - Prefix not found in database
     - Phone number is too short (< 2 digits)
     - Database error occurs

4. **Performance**:
   - The async version is recommended for UI operations
   - Database queries are fast but should not block the UI thread

## Integration Example

Here's a complete example of using location lookup in a Fragment:

```kotlin
class MyFragment : Fragment() {
    private lateinit var locationTextView: TextView
    
    fun displayPhoneLocation(phoneNumber: String) {
        phoneNumber.getLocationByPrefixAsync(requireContext()) { location ->
            locationTextView.text = location ?: "Location not available"
            locationTextView.visibility = View.VISIBLE
        }
    }
}
```

## See Also

- `PHONE_NUMBER_FORMAT_GUIDE.md` - For phone number formatting
- `String.formatPhoneNumberWithDistrict()` - For formatting phone numbers
- `String.formatPhoneNumberWithDistrictAsync()` - Async version of formatting

