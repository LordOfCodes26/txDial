# Storage Permissions Guide

## Overview
This guide explains the storage permissions added to enable call recording with flexible save locations, including access to all files on Android 11+.

## Permissions Added

### 1. MANAGE_EXTERNAL_STORAGE (Android 11+)
```xml
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
    tools:ignore="ScopedStorage" />
```

**Purpose**: Allows the app to access all files on external storage on Android 11+ (API 30+).

**Required For**:
- Saving recordings to custom locations (Music, Documents, Downloads, etc.)
- Accessing public directories for recordings
- Opening recordings folder with file managers

**Note**: This is a special permission that requires explicit user consent through system settings.

### 2. READ_EXTERNAL_STORAGE (Android 10 and below)
```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

**Purpose**: Read files from external storage on Android 10 and below.

### 3. WRITE_EXTERNAL_STORAGE (Android 10 and below)
```xml
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

**Purpose**: Write files to external storage on Android 10 and below.

## Application Attributes

### requestLegacyExternalStorage
```xml
android:requestLegacyExternalStorage="true"
```

**Purpose**: Enables legacy storage access on Android 10 (API 29) for backward compatibility.

## Queries Section

Added queries to allow the app to interact with file managers and storage settings:

```xml
<queries>
    <!-- File manager / file browser apps -->
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:mimeType="resource/folder" />
    </intent>
    
    <!-- Document picker -->
    <intent>
        <action android:name="android.intent.action.OPEN_DOCUMENT" />
        <data android:mimeType="*/*" />
    </intent>
    
    <!-- Settings for storage access -->
    <intent>
        <action android:name="android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION" />
    </intent>
</queries>
```

**Purpose**: 
- Allows opening recordings folder in file managers
- Enables document picker for custom path selection
- Allows navigating to storage permission settings

## How It Works by Android Version

### Android 13+ (API 33+)
- Uses scoped storage by default
- `MANAGE_EXTERNAL_STORAGE` required for broad access
- User grants permission through Settings → Apps → [App Name] → Permissions → Files and media → Allow management of all files

### Android 11-12 (API 30-32)
- Scoped storage enforced
- `MANAGE_EXTERNAL_STORAGE` required for broad access
- Legacy storage access not available

### Android 10 (API 29)
- Scoped storage introduced but optional
- `requestLegacyExternalStorage="true"` enables legacy behavior
- Falls back to `WRITE_EXTERNAL_STORAGE` permission

### Android 9 and below (API 28-)
- Legacy storage access
- Uses `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`
- No scoped storage restrictions

## Recording Save Locations

With these permissions, the app can save recordings to:

1. **App-specific directory** (No special permission needed)
   - `/storage/emulated/0/Android/data/com.android.dialer/files/Recordings/`
   - Automatically deleted when app is uninstalled

2. **Music folder** (Requires `MANAGE_EXTERNAL_STORAGE` on Android 11+)
   - `/storage/emulated/0/Music/Call Recordings/`

3. **Documents folder** (Requires `MANAGE_EXTERNAL_STORAGE` on Android 11+)
   - `/storage/emulated/0/Documents/Call Recordings/`

4. **Recordings folder** (Requires `MANAGE_EXTERNAL_STORAGE` on Android 11+)
   - `/storage/emulated/0/Recordings/`

5. **Custom path** (Requires `MANAGE_EXTERNAL_STORAGE` on Android 11+)
   - User-defined location

## Permission Request Flow

The app already implements permission handling in `RecordingSettingsActivity.kt`:

### For Android 11+ (API 30+):
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    if (Environment.isExternalStorageManager()) {
        // Permission granted
    } else {
        // Show dialog explaining the need
        ConfirmationDialog(...) {
            // Navigate to system settings
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }
}
```

### For Android 10 and below:
```kotlin
handlePermission(PERMISSION_WRITE_STORAGE) { granted ->
    if (granted) {
        // Save to external storage
    } else {
        // Show error or use app-specific storage
    }
}
```

## Play Store Considerations

⚠️ **Important**: `MANAGE_EXTERNAL_STORAGE` is a restricted permission on Google Play Store.

### Play Store Requirements:
1. **Declaration Form**: Must fill out a declaration form explaining why the app needs this permission
2. **Valid Use Cases**: Only approved for specific use cases:
   - ✅ File managers
   - ✅ Backup and restore apps
   - ✅ Document management apps
   - ✅ On-device file search
   - ✅ Disk and file encryption
   - ✅ Device-to-device data migration
   - ✅ **Call recording apps** (if local storage is needed)

3. **Alternative**: If not approved, use **MediaStore API** or **Storage Access Framework (SAF)** for scoped access

### Recommended Approach for Play Store:
```kotlin
// Use MediaStore for public directories
val contentValues = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, "recording.wav")
    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
    put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/Call Recordings")
}

val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
```

## Testing

### Check Permission Status:
```kotlin
// Android 11+
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    val hasPermission = Environment.isExternalStorageManager()
}

// Android 10 and below
val hasPermission = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.WRITE_EXTERNAL_STORAGE
) == PackageManager.PERMISSION_GRANTED
```

### Grant Permission Manually (ADB):
```bash
# Android 11+
adb shell appops set com.android.dialer MANAGE_EXTERNAL_STORAGE allow

# Android 10 and below
adb shell pm grant com.android.dialer android.permission.WRITE_EXTERNAL_STORAGE
```

### Revoke Permission (ADB):
```bash
# Android 11+
adb shell appops set com.android.dialer MANAGE_EXTERNAL_STORAGE deny
```

## User Privacy

### Transparency:
- Show clear explanation before requesting permission
- Explain exactly where recordings will be saved
- Provide option to use app-specific storage (no permission needed)

### Best Practices:
1. **Default to app-specific storage** - No permission required
2. **Request permission only when user chooses external storage**
3. **Explain the benefit**: "Save recordings to Music folder for easy access"
4. **Provide alternative**: "Use app storage (no permission needed)"

## Troubleshooting

### Permission Denied
**Problem**: User denied `MANAGE_EXTERNAL_STORAGE` permission

**Solution**: 
1. Fall back to app-specific storage
2. Show explanation dialog
3. Provide "Settings" button to re-request

### File Not Found
**Problem**: Can't create recording file

**Solution**:
1. Check if directory exists, create if needed
2. Verify write permissions
3. Check available storage space
4. Use app-specific storage as fallback

### Play Store Rejection
**Problem**: App rejected due to `MANAGE_EXTERNAL_STORAGE`

**Solution**:
1. Fill out declaration form with valid use case
2. Provide screenshots showing recording save location UI
3. Consider using MediaStore API as alternative
4. Document why scoped storage is insufficient

## Security Considerations

### Data Protection:
- Recordings may contain sensitive information
- Consider encryption for saved files
- Clear temp files after processing
- Respect user's storage location choice

### Privacy Policy:
Update your privacy policy to mention:
- Call recordings are stored locally on device
- User controls where recordings are saved
- No recordings are uploaded to servers (if applicable)
- Data retention and deletion policies

## Summary

✅ **MANAGE_EXTERNAL_STORAGE**: Broad file access on Android 11+  
✅ **READ/WRITE_EXTERNAL_STORAGE**: Legacy access for Android 10 and below  
✅ **requestLegacyExternalStorage**: Android 10 compatibility  
✅ **Queries**: File manager and settings intents  
✅ **Implemented**: Permission request flow in RecordingSettingsActivity  
⚠️ **Play Store**: May require declaration or alternative approach  
🔒 **Privacy**: Always respect user's storage choices  

The app now has full storage access capabilities for flexible call recording save locations! 🎉

