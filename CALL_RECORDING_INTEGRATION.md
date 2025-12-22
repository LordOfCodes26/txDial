# Call Recording Integration - BCR Logic

This document describes the integration of BCR (Basic Call Recorder) logic into your Dialer app.

## ✅ What Was Integrated

### 1. Core Recording Engine
- **File**: `app/src/main/kotlin/com/android/dialer/recording/CallRecorder.kt`
- **Purpose**: Handles audio capture, encoding, and file management
- **Features**:
  - Automatic call recording
  - Multiple audio formats (WAV, Opus, AAC, FLAC)
  - Pause/resume support (when call is on hold)
  - Recording statistics and metadata
  - JSON metadata files for each recording

### 2. Service Integration
- **File**: `app/src/main/kotlin/com/android/dialer/services/CallService.kt`
- **Changes**: Added call recording lifecycle management
  - Starts recording when call becomes ACTIVE
  - Pauses when call is on HOLD
  - Stops when call DISCONNECTS

### 3. Permissions
- **File**: `app/src/main/AndroidManifest.xml`
- **Added**:
  ```xml
  <uses-permission android:name="android.permission.CONTROL_INCALL_EXPERIENCE" />
  <uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT" />
  ```

### 4. Configuration
- **Files**: 
  - `app/src/main/kotlin/com/android/dialer/helpers/Constants.kt`
  - `app/src/main/kotlin/com/android/dialer/helpers/Config.kt`
- **Settings Added**:
  - `callRecordingEnabled` - Enable/disable recording
  - `callRecordingFormat` - Select audio format (0=WAV, 1=Opus, 2=AAC, 3=FLAC)

### 5. Documentation
- **File**: `app/src/main/kotlin/com/android/dialer/recording/README.md`
- Complete documentation of the recording system

## 🚀 How to Use

### Step 1: Enable Recording in Code

In your app's settings or configuration, set:

```kotlin
config.callRecordingEnabled = true
config.callRecordingFormat = 0 // 0 = WAV (recommended for now)
```

### Step 2: Install as System App

**⚠️ CRITICAL**: Call recording requires system-level permissions. Your app must be installed as a system app.

#### Option A: Using Magisk (Recommended for testing)

1. Build your APK:
   ```bash
   ./gradlew assembleDebug
   ```

2. Create a Magisk module structure:
   ```
   BCR-Dialer/
   ├── module.prop
   ├── install.sh
   └── system/
       └── priv-app/
           └── Dialer/
               └── Dialer.apk
   ```

3. `module.prop` content:
   ```
   id=bcr-dialer
   name=Dialer with Call Recording
   version=1.0
   versionCode=1
   author=YourName
   description=Dialer app with BCR call recording functionality
   ```

4. Zip and install via Magisk

#### Option B: Manual Root Installation

```bash
adb root
adb remount
adb push app/build/outputs/apk/debug/app-debug.apk /system/priv-app/Dialer/Dialer.apk
adb shell chmod 644 /system/priv-app/Dialer/Dialer.apk
adb reboot
```

### Step 3: Test Recording

1. Make or receive a call
2. Call will be recorded automatically
3. Check recordings in:
   ```
   /storage/emulated/0/Android/data/com.android.dialer/files/Recordings/
   ```

## 📂 Output Files

Each recording generates two files:

### Audio File
```
20231221_143025.123+0000_out_1234567890.wav
```
- Timestamp: `20231221_143025.123+0000`
- Direction: `in` (incoming) or `out` (outgoing)
- Phone Number: `1234567890`
- Extension: `.wav`, `.oga`, `.m4a`, or `.flac`

### Metadata File (JSON)
```json
{
  "timestamp": "Thu Dec 21 14:30:25 UTC 2023",
  "direction": "outgoing",
  "phone_number": "+1234567890",
  "format": "WAV",
  "sample_rate": 48000,
  "frames_total": 96000,
  "frames_encoded": 96000,
  "buffer_overruns": 0,
  "was_ever_paused": false,
  "was_ever_holding": false,
  "duration_secs_total": 2.0,
  "duration_secs_encoded": 2.0
}
```

## ⚙️ Configuration Options

### Enable/Disable Recording

**Via Code**:
```kotlin
context.config.callRecordingEnabled = true  // Enable
context.config.callRecordingEnabled = false // Disable
```

**Via SharedPreferences**:
```kotlin
val prefs = getSharedPreferences("_preferences", Context.MODE_PRIVATE)
prefs.edit().putBoolean("call_recording_enabled", true).apply()
```

### Change Output Format

```kotlin
// 0 = WAV (uncompressed, works now)
// 1 = OGG Opus (compressed, needs implementation)
// 2 = M4A AAC (compressed, needs implementation)  
// 3 = FLAC (lossless compressed, needs implementation)
context.config.callRecordingFormat = 0
```

**Note**: Currently only WAV format is fully implemented. Other formats use WAV as fallback.

## 🔧 Current Implementation Status

### ✅ Fully Implemented
- WAV audio encoding (uncompressed)
- Automatic recording on call active
- Pause/resume on hold
- Metadata generation
- File naming with timestamp and phone number
- Recording statistics

### ⚠️ Needs Implementation
- **Opus Encoding**: Requires MediaCodec or native Opus library
- **AAC Encoding**: Requires MediaCodec implementation
- **FLAC Encoding**: Requires encoder library
- **Settings UI**: Add user-facing toggle in settings
- **Notification**: Show indicator during recording
- **Legal Disclaimer**: Add consent mechanism

## 📱 Adding UI for Settings

To add a toggle in your settings, create a preference item:

**Example in Settings XML**:
```xml
<SwitchPreference
    android:key="call_recording_enabled"
    android:title="Call Recording"
    android:summary="Automatically record all calls"
    android:defaultValue="false" />

<ListPreference
    android:key="call_recording_format"
    android:title="Recording Format"
    android:entries="@array/recording_formats"
    android:entryValues="@array/recording_format_values"
    android:defaultValue="0"
    android:dependency="call_recording_enabled" />
```

**Arrays in strings.xml**:
```xml
<string-array name="recording_formats">
    <item>WAV (Uncompressed)</item>
    <item>Opus (High Quality)</item>
    <item>AAC (Good Quality)</item>
    <item>FLAC (Lossless)</item>
</string-array>

<integer-array name="recording_format_values">
    <item>0</item>
    <item>1</item>
    <item>2</item>
    <item>3</item>
</integer-array>
```

## 🐛 Debugging

### Check if Recording Started

Monitor logcat:
```bash
adb logcat | grep CallRecorder
```

Expected output:
```
I/CallRecorder: Recording started for call
I/CallRecorder: Recording stopped. File: /storage/.../20231221_143025.123+0000_out_1234567890.wav
```

### Verify System Permissions

```bash
adb shell dumpsys package com.android.dialer | grep permission
```

Look for:
```
android.permission.CONTROL_INCALL_EXPERIENCE: granted=true
android.permission.CAPTURE_AUDIO_OUTPUT: granted=true
```

### Check App Installation Type

```bash
adb shell pm list packages -s | grep dialer
```

Should show your dialer package (means it's a system app).

## ⚖️ Legal Compliance

**⚠️ IMPORTANT LEGAL NOTICE**:

Call recording laws vary by country and region:

- **Two-Party Consent**: Some jurisdictions require ALL parties to consent
- **One-Party Consent**: Some only require YOU to consent
- **Prohibited**: Some jurisdictions ban call recording entirely

### Recommended Safety Measures

1. **Add Disclosure**: Show users that recording is enabled
2. **Add Notification**: Display persistent notification during recording
3. **Add Beep Tone**: Play periodic beep to indicate recording
4. **Add Consent UI**: Let users opt-in explicitly
5. **Add Documentation**: Include terms of use about recording

### Example Disclaimer

```kotlin
val disclaimer = """
    This app includes call recording functionality.
    You are responsible for complying with local laws regarding call recording.
    By enabling this feature, you confirm that:
    
    1. You have obtained necessary consent from all parties
    2. Call recording is legal in your jurisdiction
    3. You will use recordings only for lawful purposes
    
    Do you agree?
"""
```

## 📊 File Size Estimates

Based on 48kHz mono audio:

| Format | Quality | Size per minute | Implementation Status |
|--------|---------|----------------|----------------------|
| WAV    | Lossless | ~10 MB | ✅ Complete |
| Opus   | High (lossy) | ~1-2 MB | ⚠️ Needs encoder |
| AAC    | Good (lossy) | ~1-2 MB | ⚠️ Needs encoder |
| FLAC   | Lossless | ~5 MB | ⚠️ Needs encoder |

## 🔮 Future Enhancements

Potential improvements to consider:

- [ ] Implement Opus/AAC/FLAC encoders
- [ ] Add recording indicator notification
- [ ] Add per-contact recording rules
- [ ] Add automatic cloud backup
- [ ] Add recording search and management UI
- [ ] Add encryption for sensitive recordings
- [ ] Add automatic cleanup (delete old recordings)
- [ ] Add recording playback UI
- [ ] Add audio transcription (speech-to-text)
- [ ] Add call tagging and notes

## 🆘 Troubleshooting

### "Recording not starting"

**Causes**:
1. App not installed as system app
2. Permissions not granted
3. Recording disabled in config
4. Audio source not available on device

**Solutions**:
- Verify system app installation
- Check permissions with `dumpsys`
- Enable `config.callRecordingEnabled = true`
- Check logcat for errors

### "Audio quality is poor"

**Causes**:
1. Device doesn't properly support VOICE_CALL source
2. Buffer overruns due to CPU load
3. Network issues (for VoLTE calls)

**Solutions**:
- Check buffer_overruns in metadata file
- Try different sample rate (44100 instead of 48000)
- Reduce background processes during call

### "Permission denied errors"

**Error**: `SecurityException: CAPTURE_AUDIO_OUTPUT permission denied`

**Cause**: App is not a system app

**Solution**: Must install as system app (see Step 2 above)

## 📄 License

This implementation is based on [BCR](https://github.com/chenxiaolong/BCR) and is licensed under **GPL-3.0**.

Your entire project must also be GPL-3.0 compatible when using this code.

## 🙏 Credits

- **Original BCR**: [chenxiaolong/BCR](https://github.com/chenxiaolong/BCR)
- **License**: GPL-3.0

---

For detailed technical documentation, see: `app/src/main/kotlin/com/android/dialer/recording/README.md`

