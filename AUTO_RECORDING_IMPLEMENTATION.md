# Auto-Recording Implementation

## Overview
This document describes the auto-recording logic implementation, similar to BCR (Basic Call Recorder).

## Features Added

### 1. Auto-Recording Rules
The system now supports automatic call recording based on configurable rules:

- **Disabled (Manual only)**: Recording must be triggered manually (future feature)
- **All calls**: Automatically record all incoming and outgoing calls
- **Unknown numbers only**: Record only calls from numbers not in contacts
- **Known contacts only**: Record only calls from numbers in contacts

### 2. New Files

#### `AutoRecordingHelper.kt`
**Location**: `app/src/main/kotlin/com/android/dialer/recording/AutoRecordingHelper.kt`

**Purpose**: Determines whether a call should be recorded based on the configured rule.

**Key Methods**:
- `shouldRecordCall(context, call)`: Main decision method
- `isNumberInContacts(context, phoneNumber)`: Checks if number exists in contacts
- `getCurrentRuleName(context)`: Gets the localized name of the current rule

### 3. Modified Files

#### Constants.kt
Added new constants:
```kotlin
const val CALL_RECORDING_AUTO_RULE = "call_recording_auto_rule"
const val RECORDING_RULE_NONE = 0
const val RECORDING_RULE_ALL_CALLS = 1
const val RECORDING_RULE_UNKNOWN_NUMBERS = 2
const val RECORDING_RULE_KNOWN_CONTACTS = 3
```

#### Config.kt
Added new configuration property:
```kotlin
var callRecordingAutoRule: Int
    get() = prefs.getInt(CALL_RECORDING_AUTO_RULE, RECORDING_RULE_ALL_CALLS)
    set(callRecordingAutoRule) = prefs.edit { putInt(CALL_RECORDING_AUTO_RULE, callRecordingAutoRule) }
```

#### CallService.kt
Updated `handleRecordingState()` to check auto-recording rule before starting:
```kotlin
if (AutoRecordingHelper.shouldRecordCall(this, call)) {
    startRecording(call)
} else {
    Log.i("CallService", "Call does not match auto-recording rule, skipping")
}
```

#### RecordingSettingsActivity.kt
- Added UI for selecting auto-recording rule
- Added `showAutoRecordingRulePicker()` method
- Updated `updateUI()` to display current rule

#### activity_recording_settings.xml
- Added new "Auto-record calls" selector in the General section
- Shows current auto-recording rule

#### strings.xml
Added new strings:
- `auto_recording_rule`: "Auto-record calls"
- `recording_rule_none`: "Disabled (Manual only)"
- `recording_rule_all`: "All calls"
- `recording_rule_unknown`: "Unknown numbers only"
- `recording_rule_known`: "Known contacts only"
- `recording_rule_description`: "Choose which calls to automatically record"

## How It Works

```
Call Becomes ACTIVE
         ↓
Check if recording enabled?
    ↓ NO → Skip
    ↓ YES
         ↓
Check auto-recording rule:
    ├─ NONE → Skip recording
    ├─ ALL CALLS → Start recording ✅
    ├─ UNKNOWN NUMBERS → Check if in contacts
    │    ↓ NOT IN CONTACTS → Start recording ✅
    │    ↓ IN CONTACTS → Skip recording
    └─ KNOWN CONTACTS → Check if in contacts
         ↓ IN CONTACTS → Start recording ✅
         ↓ NOT IN CONTACTS → Skip recording
```

## Usage

### Setting Auto-Recording Rule

#### Via UI:
1. Open Settings → Call Recording
2. Tap "Auto-record calls"
3. Select your preferred rule:
   - Disabled (Manual only)
   - All calls
   - Unknown numbers only
   - Known contacts only

#### Programmatically:
```kotlin
// Record all calls
config.callRecordingAutoRule = RECORDING_RULE_ALL_CALLS

// Record only unknown numbers
config.callRecordingAutoRule = RECORDING_RULE_UNKNOWN_NUMBERS

// Record only known contacts
config.callRecordingAutoRule = RECORDING_RULE_KNOWN_CONTACTS

// Disable auto-recording (manual only)
config.callRecordingAutoRule = RECORDING_RULE_NONE
```

## Contact Detection

The system uses Android's `ContactsContract.PhoneLookup` to determine if a phone number exists in contacts:

1. Normalizes the phone number (removes formatting)
2. Queries the contacts database
3. Returns true if any contact has this number
4. Returns false if no match or error occurs

**Note**: Requires `READ_CONTACTS` permission (already declared in manifest)

## Default Behavior

- **Default rule**: `RECORDING_RULE_ALL_CALLS` (record all calls)
- If recording is enabled but rule check fails, the call is NOT recorded
- Error handling: If contact lookup fails, assumes number is NOT in contacts

## Benefits

✅ **Flexible**: Choose exactly which calls to record  
✅ **Privacy-friendly**: Option to record only unknown numbers (potential spam)  
✅ **Selective**: Option to record only known contacts  
✅ **Automatic**: No need to manually start recording  
✅ **Compatible**: Works seamlessly with existing recording system  
✅ **BCR-like**: Similar to BCR's auto-recording functionality

## Testing

To test auto-recording rules:

1. **All calls**: Make/receive any call → Should auto-record
2. **Unknown numbers**: 
   - Call from a saved contact → Should NOT record
   - Call from unsaved number → Should auto-record
3. **Known contacts**:
   - Call from a saved contact → Should auto-record
   - Call from unsaved number → Should NOT record
4. **Disabled**: No calls should auto-record

## Future Enhancements

Possible future improvements:
- Per-contact recording preferences
- White/blacklist specific numbers
- Time-based rules (e.g., only record during business hours)
- Manual recording trigger during call
- Group-based rules (e.g., record all work contacts)

