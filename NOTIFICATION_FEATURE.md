# Call Recording Notification Feature

## Overview

The call recording notification feature displays a persistent notification while recording is active, similar to BCR (Basic Call Recorder). This provides visual feedback to users that their call is being recorded.

## What Was Added

### 1. RecordingNotificationManager
**File**: `app/src/main/kotlin/com/android/dialer/recording/RecordingNotificationManager.kt`

A dedicated notification manager for call recordings that:
- Creates a notification channel for recording notifications
- Shows persistent notification during recording
- Updates notification when recording is paused/resumed
- Automatically dismisses when recording stops

### 2. Microphone Icon
**File**: `app/src/main/res/drawable/ic_microphone_vector.xml`

A vector drawable icon used in the recording notification.

### 3. String Resources
**File**: `app/src/main/res/values/strings.xml`

Added notification text strings:
- `recording_call` - "Recording call"
- `recording_paused` - "Recording paused"
- `recording_in_progress` - "Recording in progress"
- `recording_phone_number` - "Recording: %s"
- `call_recording_notification_channel` - "Call Recording"
- `call_recording_notification_description` - "Shows when a call is being recorded"

### 4. Integration Updates

**CallRecorder.kt**:
- Added notification manager parameter
- Shows notification when recording starts
- Updates notification when paused/resumed
- Hides notification when recording stops

**CallService.kt**:
- Creates RecordingNotificationManager instance
- Passes it to CallRecorder on creation

## Notification Behavior

### 📱 During Recording (Active)

```
┌─────────────────────────────────┐
│ 🎤 Recording call        00:32  │
│ Recording: +1 234-567-8900      │
└─────────────────────────────────┘
```

**Features**:
- ✅ Shows microphone icon
- ✅ Displays "Recording call" title
- ✅ Shows phone number being recorded
- ✅ Shows elapsed time (chronometer)
- ✅ Cannot be dismissed (ongoing notification)
- ✅ Tapping opens the app

### ⏸️ During Pause (On Hold)

```
┌─────────────────────────────────┐
│ 🎤 Recording paused             │
│ Recording: +1 234-567-8900      │
│ On Hold                         │
└─────────────────────────────────┘
```

**Features**:
- ✅ Changes title to "Recording paused"
- ✅ Stops chronometer
- ✅ Shows "On Hold" subtitle
- ✅ Still cannot be dismissed

### ✅ When Call Ends

- Notification is automatically removed
- Recording file is saved
- Metadata is generated

## Notification Channel

**Channel ID**: `call_recording`  
**Channel Name**: "Call Recording"  
**Importance**: LOW (doesn't make sound or vibrate)

### Channel Settings

- **Show Badge**: No
- **Sound**: Silent
- **Vibration**: Disabled
- **Lights**: Disabled
- **Importance**: Low (non-intrusive)

Users can customize these settings in:
`Settings → Apps → Phone → Notifications → Call Recording`

## Technical Details

### Notification Properties

```kotlin
NotificationCompat.Builder(context, CHANNEL_ID)
    .setSmallIcon(R.drawable.ic_microphone_vector)
    .setContentTitle("Recording call")
    .setContentText("Recording: +1 234-567-8900")
    .setOngoing(true)                              // Can't dismiss
    .setCategory(NotificationCompat.CATEGORY_CALL) // Call category
    .setPriority(NotificationCompat.PRIORITY_LOW)  // Non-intrusive
    .setShowWhen(true)                             // Show time
    .setUsesChronometer(!isPaused)                 // Timer when recording
    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
```

### State Flow

```
Call Starts
    ↓
Recording Starts
    ↓
🎤 Notification Shown (with timer)
    ↓
Call On Hold?
    ↓
⏸️ Notification Updated (paused, timer stops)
    ↓
Call Active Again?
    ↓
🎤 Notification Updated (recording, timer resumes)
    ↓
Call Ends
    ↓
❌ Notification Hidden
```

## User Benefits

### 🔒 Transparency
- Users always know when recording is active
- Meets legal requirements for recording disclosure
- Provides visual confirmation

### ⏱️ Duration Tracking
- Shows elapsed recording time
- Helps users track call length
- Updates in real-time

### 📊 Status Awareness
- Clear indication when paused (on hold)
- Shows phone number being recorded
- Can't be accidentally dismissed

### 🎯 Quick Access
- Tapping notification opens the app
- Easy access to call controls
- Convenient for multi-tasking

## Customization Options

### Change Notification Icon

Edit `RecordingNotificationManager.kt`:

```kotlin
.setSmallIcon(R.drawable.ic_your_custom_icon)
```

### Change Notification Priority

```kotlin
.setPriority(NotificationCompat.PRIORITY_HIGH)  // More prominent
```

### Add Action Buttons

```kotlin
// Example: Add stop recording button
val stopIntent = Intent(context, CallService::class.java).apply {
    action = RecordingNotificationManager.ACTION_STOP_RECORDING
}
val stopPendingIntent = PendingIntent.getService(...)

builder.addAction(
    R.drawable.ic_stop,
    "Stop Recording",
    stopPendingIntent
)
```

### Change Notification Color

```kotlin
.setColor(context.getColor(R.color.your_custom_color))
```

## Legal Compliance

### ⚖️ Why This Matters

Many jurisdictions require:
- **Visual indication** that recording is active
- **Audio beep** or **notification** during recording
- **Consent** from all parties

This notification helps comply with these requirements by:
- ✅ Providing clear visual indication
- ✅ Showing recording status persistently
- ✅ Preventing accidental dismissal

### 🌍 Regional Requirements

| Region | Requirement | Notification Helps? |
|--------|------------|-------------------|
| USA (Most States) | One-party consent + disclosure | ✅ Yes |
| EU (GDPR) | Two-party consent + notification | ✅ Yes |
| Canada | One-party consent + disclosure | ✅ Yes |
| Australia | One-party consent + notification | ✅ Yes |
| UK | Two-party consent + disclosure | ✅ Yes |

**Note**: This notification alone may not be sufficient for legal compliance. Consult local laws and add additional measures as needed.

## Testing

### Test Scenarios

1. **Start Recording**
   - Make/receive a call
   - Verify notification appears when call becomes active
   - Check timer is running

2. **Pause Recording**
   - Put call on hold
   - Verify notification changes to "paused"
   - Check timer stops

3. **Resume Recording**
   - Resume call from hold
   - Verify notification changes back to "recording"
   - Check timer resumes

4. **End Recording**
   - End the call
   - Verify notification disappears
   - Check recording file is saved

### Debug Commands

**Check notification is showing**:
```bash
adb shell dumpsys notification | grep "com.android.dialer"
```

**View notification channel**:
```bash
adb shell cmd notification list_channels com.android.dialer call_recording
```

**Test notification directly**:
```kotlin
// In your test code
val notificationManager = RecordingNotificationManager(context)
notificationManager.showRecordingNotification("+1234567890", false)
```

## Common Issues

### Notification Not Showing

**Symptoms**:
- Recording works but no notification appears

**Causes**:
1. Notification permission not granted (Android 13+)
2. Notification channel disabled
3. App-level notifications disabled

**Solutions**:
```bash
# Check notification permission
adb shell dumpsys package com.android.dialer | grep NOTIFICATIONS

# Re-enable notification channel
adb shell cmd notification allow_listener com.android.dialer
```

### Notification Dismissed Too Easily

**Symptom**:
- User can swipe away notification

**Cause**:
- `setOngoing(true)` not set

**Solution**:
Already fixed in code - notification is set as ongoing.

### Timer Not Updating

**Symptom**:
- Chronometer shows "00:00" and doesn't update

**Cause**:
- `setUsesChronometer()` not enabled or base time not set

**Solution**:
- Ensure `setShowWhen(true)` and `setUsesChronometer(true)` are called
- System handles timer automatically

## Future Enhancements

Potential improvements:

- [ ] **Action Buttons**: Add pause/stop buttons to notification
- [ ] **Rich Preview**: Show waveform or audio level
- [ ] **File Size**: Display current recording size
- [ ] **Quality Indicator**: Show recording quality/format
- [ ] **Progress Bar**: Visual progress indicator
- [ ] **Custom Sound**: Optional recording start/stop sound
- [ ] **LED Indicator**: Flash LED during recording (if supported)
- [ ] **Expandable Details**: Show more info when expanded

## Example Implementation

### Basic Usage

```kotlin
// In CallService
val notificationManager = RecordingNotificationManager(context)

// Start recording
val recorder = CallRecorder(
    context = context,
    call = call,
    outputFormat = CallRecorder.OutputFormat.WAV,
    notificationManager = notificationManager
)
recorder.startRecording()  // Notification shown automatically

// Pause recording
recorder.pauseRecording()  // Notification updated to "paused"

// Resume recording
recorder.resumeRecording() // Notification updated to "recording"

// Stop recording
recorder.stopRecording()   // Notification hidden automatically
```

### Manual Control (Advanced)

```kotlin
val notificationManager = RecordingNotificationManager(context)

// Show notification manually
notificationManager.showRecordingNotification("+1234567890", isPaused = false)

// Update state
notificationManager.updateNotification("+1234567890", isPaused = true)

// Hide notification
notificationManager.cancelNotification()
```

## Summary

✅ **Notification system integrated and working**  
✅ **Shows during recording with timer**  
✅ **Updates when paused/resumed**  
✅ **Automatically hidden when recording stops**  
✅ **Helps with legal compliance**  
✅ **User-friendly and non-intrusive**

The notification feature is now fully integrated and will automatically display whenever call recording is active, providing transparency and better user experience! 🎉

