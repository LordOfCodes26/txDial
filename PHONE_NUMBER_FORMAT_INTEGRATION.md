# Phone Number Format System Integration

This document explains how the database-based phone number format system is integrated with the existing `PhoneNumberFormatManager` in the commons library.

## Integration Overview

The database-based phone number format system is now integrated with `PhoneNumberFormatManager` through the `DatabasePhoneNumberFormatter` class, which implements the `PhoneNumberFormatter` interface.

## How It Works

### 1. DatabasePhoneNumberFormatter

A new formatter class (`DatabasePhoneNumberFormatter`) implements `PhoneNumberFormatter` and uses the database format system:

```kotlin
class DatabasePhoneNumberFormatter(context: Context) : PhoneNumberFormatter {
    override fun formatPhoneNumber(...): String {
        // 1. Try to match phone number against database formats
        // 2. If match found, format using database template
        // 3. If no match, fall back to Android's default formatting
    }
}
```

### 2. Integration with PhoneNumberFormatManager

The formatter is set as the custom formatter in `PhoneNumberFormatManager`:

```kotlin
// In App.onCreate()
PhoneNumberFormatManager.customFormatter = DatabasePhoneNumberFormatter(context)
```

### 3. Automatic Usage

Once set, all calls to `formatPhoneNumber()` will automatically use the database formats:

```kotlin
// This now uses DatabasePhoneNumberFormatter automatically
phoneNumber.formatPhoneNumber()
```

## Usage Options

### Option 1: Use formatPhoneNumber() (Recommended for Background Threads)

Since `DatabasePhoneNumberFormatter` is set as the custom formatter, `formatPhoneNumber()` will automatically use database formats:

```kotlin
// Synchronous - use from background thread
ensureBackgroundThread {
    val formatted = phoneNumber.formatPhoneNumber()
}
```

### Option 2: Use formatPhoneNumberWithDistrict() (Direct Database Access)

For direct database access (must be called from background thread):

```kotlin
ensureBackgroundThread {
    val formatted = phoneNumber.formatPhoneNumberWithDistrict(context)
}
```

### Option 3: Use formatPhoneNumberWithDistrictAsync() (Recommended for UI Thread)

For async formatting from UI thread:

```kotlin
phoneNumber.formatPhoneNumberWithDistrictAsync(context) { formatted ->
    textView.text = formatted
}
```

## Format Priority

When `DatabasePhoneNumberFormatter` is active, the formatting priority is:

1. **Database Formats**: Try to match against formats in `phone_number_formats` table
2. **Default Formatter**: If no database match, fall back to `PhoneNumberFormatManager`'s default formatter (191/195 patterns or Android default)

## Benefits

1. **Unified API**: All formatting goes through `PhoneNumberFormatManager`
2. **Automatic Integration**: Existing code using `formatPhoneNumber()` automatically gets database formatting
3. **Fallback Support**: If database has no formats or match fails, falls back to default formatting
4. **Flexible**: Can still use direct database methods (`formatPhoneNumberWithDistrict`) if needed

## Example: App Initialization

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize database
        PhoneNumberDatabase.getInstance(this)
        
        // Set database formatter as custom formatter
        PhoneNumberFormatManager.customFormatter = DatabasePhoneNumberFormatter(this)
        
        // Load format data
        val helper = PhonePrefixLocationHelper(this)
        helper.loadFormatsFromRaw(R.raw.phone_number_formats) { count ->
            Log.d("App", "Loaded $count formats")
        }
    }
}
```

## Migration Notes

- Existing code using `formatPhoneNumber()` will automatically use database formats once `DatabasePhoneNumberFormatter` is set
- Code using `formatPhoneNumberWithDistrict()` or `formatPhoneNumberWithDistrictAsync()` continues to work as before
- The async version is recommended for UI thread operations to avoid blocking

