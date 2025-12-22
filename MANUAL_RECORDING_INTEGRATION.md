# Manual Call Recording Integration

## Overview
This document describes the manual call recording controls integration, allowing users to start and stop recording during an active call.

## Features Added

### 1. Manual Recording Controls
Users can now manually control call recording during an active call:
- **Start Recording**: Tap the record button to begin recording
- **Stop Recording**: Tap again to stop recording
- **Visual Feedback**: Button changes appearance when recording is active (red background)
- **Status Label**: Shows "Record" when inactive, "Recording" when active

### 2. Integration Points

#### Constants.kt
Added new action constant:
```kotlin
const val RECORD_CALL = PATH + "record_call"
```

#### CallActionReceiver.kt
Added handler for recording action:
```kotlin
RECORD_CALL -> {
    CallManager.toggleRecording(context)
    CallNotificationManager(context).updateNotification()
}
```

#### CallManager.kt
Added recording state management:
```kotlin
var isRecording: Boolean = false
    private set

fun toggleRecording(context: Context)
fun updateRecordingState(recording: Boolean)
```

#### CallService.kt
Added manual recording control:
```kotlin
fun toggleRecording() {
    if (callRecorder == null) {
        // Start recording
        startRecording(currentCall)
    } else {
        // Stop recording
        stopRecording()
    }
}
```

Updated recording lifecycle to update CallManager state:
- `startRecording()`: Sets `CallManager.isRecording = true`
- `stopRecording()`: Sets `CallManager.isRecording = false`

#### CallActivity.kt
Added UI controls and handlers:
```kotlin
private fun toggleRecording() {
    CallManager.toggleRecording(this)
    updateRecordingButton()
}

private fun updateRecordingButton() {
    // Updates button appearance based on recording state
    // - Active: Red background, white icon, "Recording" label
    // - Inactive: Gray background, white icon, "Record" label
}

private fun updateRecordingButtonVisibility() {
    // Shows button only if recording is enabled in config
    val shouldShow = config.callRecordingEnabled
}
```

#### activity_call.xml
Added recording button UI:
```xml
<ImageView
    android:id="@+id/callToggleRecording"
    android:layout_width="@dimen/call_button_size"
    android:layout_height="@dimen/call_button_size"
    android:src="@drawable/ic_microphone_vector"
    android:contentDescription="@string/start_recording"
    android:visibility="gone" />

<MyTextView
    android:id="@+id/callToggleRecordingLabel"
    android:text="@string/record"
    android:visibility="gone" />
```

#### strings.xml
Added new strings:
```xml
<string name="start_recording">Start recording</string>
<string name="stop_recording">Stop recording</string>
<string name="recording_active">Recording</string>
<string name="record">Record</string>
```

## How It Works

### Recording Flow

```
User taps Record button
         ↓
CallActivity.toggleRecording()
         ↓
CallManager.toggleRecording()
         ↓
CallService.toggleRecording()
         ↓
If NOT recording:
    ├─ Create CallRecorder
    ├─ Start recording
    ├─ Update CallManager.isRecording = true
    └─ Update UI (red button)
         
If already recording:
    ├─ Stop CallRecorder
    ├─ Save file
    ├─ Update CallManager.isRecording = false
    └─ Update UI (gray button)
```

### UI State Updates

The recording button state is updated in multiple scenarios:
1. **User clicks button**: `toggleRecording()` → `updateRecordingButton()`
2. **Call state changes**: `onStateChanged()` → `updateState()` → `updateRecordingButton()`
3. **Call starts**: `callStarted()` → `updateRecordingButtonVisibility()`

### Visual Feedback

**Inactive State:**
- Gray background (60% opacity)
- White microphone icon
- Label: "Record"

**Active/Recording State:**
- Red background (100% opacity)
- White microphone icon
- Label: "Recording"

## Usage

### Enable Recording Feature
First, enable recording in settings:
```kotlin
config.callRecordingEnabled = true
```

### During a Call

1. **Start Recording**:
   - Make or receive a call
   - Wait for call to be active
   - Tap the "Record" button
   - Button turns red and shows "Recording"
   - Recording notification appears

2. **Stop Recording**:
   - Tap the "Recording" button again
   - Button returns to gray "Record" state
   - Recording is saved to configured location
   - Notification dismisses

### Button Visibility

The recording button is only visible when:
- ✅ `config.callRecordingEnabled == true`
- ✅ Call is in active state
- ✅ Not showing dialpad

The button is hidden when:
- ❌ Recording is disabled in settings
- ❌ Dialpad is open
- ❌ Call is ringing/connecting

## Integration with Auto-Recording

Manual recording works alongside auto-recording:

1. **Auto-record ALL_CALLS**: 
   - Recording starts automatically
   - Button shows "Recording" state
   - User can tap to stop early

2. **Auto-record NONE (Manual only)**:
   - Recording does NOT start automatically
   - Button shows "Record" state
   - User must tap to start

3. **Auto-record UNKNOWN/KNOWN**:
   - Recording may start automatically based on rule
   - If started automatically, button shows "Recording"
   - If not started, user can tap to start manually

## Benefits

✅ **User Control**: Manual start/stop during any call  
✅ **Visual Feedback**: Clear indication of recording state  
✅ **Flexible**: Works with or without auto-recording rules  
✅ **Intuitive**: Standard call button UI pattern  
✅ **Integrated**: Works with existing notification system  
✅ **Safe**: Respects recording enabled setting

## Testing

### Test Scenarios

1. **Manual Start/Stop**:
   - Set rule to "Disabled (Manual only)"
   - Make a call
   - Tap Record → Should start recording
   - Tap again → Should stop and save

2. **Override Auto-Recording**:
   - Set rule to "All calls"
   - Make a call → Auto-starts
   - Tap Recording button → Should stop early

3. **Button Visibility**:
   - Disable recording in settings
   - Make a call → Button should be hidden
   - Enable recording → Button should appear

4. **State Persistence**:
   - Start recording
   - Put call on hold → Recording pauses
   - Resume call → Recording resumes
   - Button state should match recording state

5. **UI Updates**:
   - Start recording
   - Check button is red with "Recording" label
   - Stop recording  
   - Check button is gray with "Record" label

## Future Enhancements

Possible improvements:
- Add recording timer on the button
- Add haptic feedback when recording starts/stops
- Add pause/resume button (separate from hold)
- Add waveform visualization
- Add quick access to recorded file after call ends
- Add confirmation dialog before stopping important recordings

