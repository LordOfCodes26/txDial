# Call Recording Feature

This directory contains the call recording implementation based on [BCR (Basic Call Recorder)](https://github.com/chenxiaolong/BCR).

## Overview

The call recording feature automatically records incoming and outgoing phone calls and saves them to the device storage.

## Architecture

### Components

1. **CallRecorder.kt** - Core recording engine
   - Captures audio from the `VOICE_CALL` audio source
   - Handles audio encoding (WAV, Opus, AAC, FLAC)
   - Manages recording state (active, paused, stopped)
   - Saves metadata in JSON format

2. **CallService.kt** - Integration point
   - Monitors call state changes
   - Starts/stops recording based on call state
   - Manages recorder lifecycle

### How It Works

1. When a call becomes `ACTIVE`, recording starts automatically
2. Audio is captured from `MediaRecorder.AudioSource.VOICE_CALL`
3. Raw PCM audio is encoded in real-time
4. When call is on `HOLD`, recording pauses
5. When call `DISCONNECTS`, recording stops and file is finalized

## System Requirements

⚠️ **IMPORTANT**: This feature requires **system-level permissions** that are only available to:
- System apps (installed in `/system/priv-app/`)
- Apps on rooted devices
- Apps on custom ROMs with elevated permissions

### Required Permissions

```xml
<!-- Standard permissions -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<!-- System permissions (require system app status) -->
<uses-permission android:name="android.permission.CONTROL_INCALL_EXPERIENCE" />
<uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT" />
```

## File Storage

Recordings are saved to:
```
/storage/emulated/0/Android/data/com.android.dialer/files/Recordings/
```

### File Naming Convention

```
YYYYMMDD_HHmmss.SSSZ_[in|out]_PHONENUMBER.EXT
```

Example: `20231221_143025.123+0000_in_1234567890.wav`

- `YYYYMMDD_HHmmss.SSSZ` - Timestamp with timezone
- `in/out` - Call direction
- `PHONENUMBER` - Phone number (sanitized)
- `EXT` - File extension (wav, oga, m4a, flac)

### Metadata Files

Each recording has an accompanying JSON metadata file:

```json
{
  "timestamp": "Thu Dec 21 14:30:25 UTC 2023",
  "direction": "incoming",
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

## Configuration

### Enable/Disable Recording

In `CallService.kt`:

```kotlin
private var recordingEnabled = true // Set to false to disable
```

### Change Output Format

In `CallService.kt`, modify the `startRecording()` method:

```kotlin
callRecorder = CallRecorder(
    context = this,
    call = call,
    outputFormat = CallRecorder.OutputFormat.WAV // or OGG_OPUS, M4A_AAC, FLAC
)
```

### Audio Quality

In `CallRecorder.kt`:

```kotlin
private const val SAMPLE_RATE = 48000 // Hz (48kHz recommended)
```

## Supported Audio Formats

### 1. WAV (Uncompressed)
- **Extension**: `.wav`
- **Quality**: Lossless
- **File Size**: Large (~10 MB per minute)
- **Status**: ✅ Fully implemented

### 2. Opus (Compressed)
- **Extension**: `.oga`
- **Quality**: High (lossy)
- **File Size**: Small (~1-2 MB per minute)
- **Status**: ⚠️ Placeholder (needs MediaCodec or native library)

### 3. AAC (Compressed)
- **Extension**: `.m4a`
- **Quality**: Good (lossy)
- **File Size**: Small (~1-2 MB per minute)
- **Status**: ⚠️ Placeholder (needs MediaCodec implementation)

### 4. FLAC (Compressed Lossless)
- **Extension**: `.flac`
- **Quality**: Lossless
- **File Size**: Medium (~5 MB per minute)
- **Status**: ⚠️ Placeholder (needs encoder library)

## Installation as System App

### Option 1: Magisk Module

1. Build the APK:
   ```bash
   ./gradlew assembleRelease
   ```

2. Create Magisk module structure:
   ```
   module/
   ├── META-INF/
   │   └── com/
   │       └── google/
   │           └── android/
   │               ├── update-binary
   │               └── updater-script
   ├── system/
   │   └── priv-app/
   │       └── Dialer/
   │           └── Dialer.apk
   ├── module.prop
   └── install.sh
   ```

3. Install via Magisk Manager

### Option 2: Manual Installation (Rooted Device)

```bash
adb root
adb remount
adb push app-release.apk /system/priv-app/Dialer/Dialer.apk
adb shell chmod 644 /system/priv-app/Dialer/Dialer.apk
adb reboot
```

### Option 3: Custom ROM

Include the APK in your ROM build:
```
device/[manufacturer]/[device]/proprietary/priv-app/Dialer/
```

## Legal Considerations

⚠️ **WARNING**: Call recording laws vary by jurisdiction!

- Some regions require **two-party consent** (all parties must consent)
- Some regions require **one-party consent** (only you need to consent)
- Some regions **prohibit** call recording entirely
- Commercial use may have additional restrictions

**You are responsible for complying with local laws regarding call recording.**

It's recommended to:
1. Add user-facing disclosure about recording
2. Implement consent mechanisms
3. Add audio/visual indicators during recording
4. Provide opt-out options

## Troubleshooting

### Recording Not Starting

**Symptom**: No recording files created

**Solutions**:
1. Check if app has system permissions:
   ```bash
   adb shell dumpsys package com.android.dialer | grep permission
   ```

2. Verify app is installed as system app:
   ```bash
   adb shell pm list packages -s | grep dialer
   ```

3. Check logs:
   ```bash
   adb logcat | grep CallRecorder
   ```

### Audio Quality Issues

**Symptom**: Recording has noise, distortion, or low volume

**Solutions**:
1. Try different audio sources (may require code changes)
2. Adjust sample rate (48000 recommended, try 44100)
3. Check for buffer overruns in metadata
4. Ensure device isn't under heavy load during recording

### Permissions Denied

**Symptom**: `SecurityException: CAPTURE_AUDIO_OUTPUT permission denied`

**Cause**: App is not installed as system app

**Solution**: Follow "Installation as System App" instructions above

### Storage Issues

**Symptom**: Recording stops prematurely or fails to save

**Solutions**:
1. Check available storage space
2. Verify directory permissions
3. Check for SELinux denials:
   ```bash
   adb shell dmesg | grep denied
   ```

## Future Improvements

- [ ] Implement proper Opus encoding (MediaCodec or native)
- [ ] Implement AAC encoding (MediaCodec)
- [ ] Implement FLAC encoding
- [ ] Add user settings UI for recording preferences
- [ ] Add notification during recording
- [ ] Implement automatic old file cleanup
- [ ] Add cloud backup integration
- [ ] Add call recording indicator in UI
- [ ] Implement selective recording (per contact)
- [ ] Add encryption for recordings

## Credits

This implementation is based on [BCR (Basic Call Recorder)](https://github.com/chenxiaolong/BCR) by chenxiaolong.

## License

This code is licensed under GPL-3.0, same as the original BCR project.

