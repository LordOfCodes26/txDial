# Phone Number Format System - Commons Library Migration

This document summarizes the migration of the phone number format and location lookup system to the commons library.

## What Was Moved

### Models (→ `commons/src/main/kotlin/com/goodwy/commons/models/`)
- `PhonePrefixLocation.kt` - City/location mappings
- `PhoneDistrict.kt` - District/area mappings  
- `PhoneNumberFormat.kt` - Phone number format definitions

### DAOs (→ `commons/src/main/kotlin/com/goodwy/commons/interfaces/`)
- `PhonePrefixLocationDao.kt`
- `PhoneDistrictDao.kt`
- `PhoneNumberFormatDao.kt`

### Database (→ `commons/src/main/kotlin/com/goodwy/commons/databases/`)
- `PhoneNumberDatabase.kt` - New standalone database for phone number data

### Helpers (→ `commons/src/main/kotlin/com/goodwy/commons/helpers/`)
- `PhonePrefixLocationHelper.kt` - Data loading and management
- `PhoneNumberFormatHelper.kt` - Pattern matching and formatting

### Extensions (→ `commons/src/main/kotlin/com/goodwy/commons/extensions/String.kt`)
- `String.getLocationByPrefix()` - Synchronous location lookup
- `String.getLocationByPrefixAsync()` - Asynchronous location lookup
- `String.formatPhoneNumberWithDistrict()` - Synchronous formatting
- `String.formatPhoneNumberWithDistrictAsync()` - Asynchronous formatting

### JSON Files (→ `commons/src/main/res/raw/`)
- `phone_prefix_locations.json` - Default city mappings
- `phone_districts.json` - Default district mappings
- `phone_number_formats.json` - Default format definitions

## How to Use in Other Projects

### 1. Add Dependencies

The commons library is already included as a module dependency. No additional setup needed.

### 2. Initialize Database

In your `Application.onCreate()`:

```kotlin
import com.goodwy.commons.helpers.PhonePrefixLocationHelper
import com.goodwy.commons.databases.PhoneNumberDatabase

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize phone number database
        PhoneNumberDatabase.getInstance(this)
        
        // Load data from JSON files
        val helper = PhonePrefixLocationHelper(this)
        
        // Load prefix locations (cities)
        helper.hasPrefixLocations { hasData ->
            if (!hasData) {
                helper.loadFromRaw(R.raw.phone_prefix_locations) { count ->
                    Log.d("App", "Loaded $count prefix locations")
                }
            }
        }
        
        // Load districts
        helper.hasDistricts { hasData ->
            if (!hasData) {
                helper.loadDistrictsFromRaw(R.raw.phone_districts) { count ->
                    Log.d("App", "Loaded $count districts")
                }
            }
        }
        
        // Load formats
        helper.hasFormats { hasData ->
            if (!hasData) {
                helper.loadFormatsFromRaw(R.raw.phone_number_formats) { count ->
                    Log.d("App", "Loaded $count formats")
                }
            }
        }
    }
}
```

### 3. Use Extensions

The extensions are automatically available via `com.goodwy.commons.extensions.*`:

```kotlin
import com.goodwy.commons.extensions.*

// Format phone number
phoneNumber.formatPhoneNumberWithDistrictAsync(context) { formatted ->
    textView.text = formatted
}

// Get location
phoneNumber.getLocationByPrefixAsync(context) { location ->
    locationTextView.text = location ?: "Unknown"
}
```

### 4. Customize Data

You can:
- Override JSON files in your app's `res/raw/` folder
- Load from assets folder: `helper.loadFromAssets("phone_prefix_locations.json")`
- Insert programmatically: `helper.insertPrefixLocations(locations)`

## Package Structure

All classes are in the `com.goodwy.commons` package:

- Models: `com.goodwy.commons.models.*`
- DAOs: `com.goodwy.commons.interfaces.*`
- Database: `com.goodwy.commons.databases.PhoneNumberDatabase`
- Helpers: `com.goodwy.commons.helpers.*`
- Extensions: `com.goodwy.commons.extensions.*`

## Database

The phone number system uses a separate database (`phone_number.db`) to avoid conflicts with app-specific databases. The database is automatically created and migrated when `PhoneNumberDatabase.getInstance(context)` is called.

## Notes

- The app's existing JSON files in `app/src/main/res/raw/` are still used for the dialer app
- Other projects can use the default JSON files from commons or provide their own
- All database operations are thread-safe and use background threads automatically
- The async extensions handle threading internally and post results to the main thread

