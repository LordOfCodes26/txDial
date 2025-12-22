# Recording Settings UI Style Consistency

## Overview
Updated `RecordingSettingsActivity` to use consistent UI styling and naming conventions with `SettingsActivity`.

## Changes Made

### 1. ID Naming Convention
Changed all layout IDs from `snake_case` to `camelCase` to match `SettingsActivity`:

**Before:**
- `settings_general_label`
- `settings_recording_card`
- `settings_recording_enabled_holder`
- `settings_recording_format_label`

**After:**
- `settingsGeneralLabel`
- `settingsRecordingGeneralHolder`
- `settingsRecordingEnabledHolder`
- `settingsRecordingFormatLabel`

### 2. Color Reference Updates
Changed `tools:textColor` to use consistent color resource:

**Before:**
```xml
tools:textColor="@color/color_primary"
```

**After:**
```xml
tools:textColor="@color/primary"
```

### 3. Wrapper LinearLayout IDs
Added IDs to wrapper LinearLayouts for consistency:

**General Section:**
- Added `settingsRecordingGeneralWrapper`

**Format & Location Section:**
- Added `settingsFormatLocationWrapper`

**File Naming Section:**
- Added `settingsFileNamingWrapper`

### 4. CardView ID Updates
Updated CardView IDs to match naming pattern:

**Before:**
- `settings_recording_card`
- `settings_format_location_card`
- `settings_file_naming_card`
- `settings_actions_card`

**After:**
- `settingsRecordingGeneralHolder`
- `settingsFormatLocationHolder`
- `settingsFileNamingHolder`
- `settingsActionsHolder`

## Complete ID Mapping

| Old ID (snake_case) | New ID (camelCase) |
|---------------------|---------------------|
| `settings_general_label` | `settingsGeneralLabel` |
| `settings_recording_card` | `settingsRecordingGeneralHolder` |
| `settings_recording_enabled_holder` | `settingsRecordingEnabledHolder` |
| `settings_recording_auto_rule_holder` | `settingsRecordingAutoRuleHolder` |
| `settings_recording_auto_rule_label` | `settingsRecordingAutoRuleLabel` |
| `settings_recording_auto_rule` | `settingsRecordingAutoRule` |
| `settings_format_location_label` | `settingsFormatLocationLabel` |
| `settings_format_location_card` | `settingsFormatLocationHolder` |
| `settings_recording_format_holder` | `settingsRecordingFormatHolder` |
| `settings_recording_format_label` | `settingsRecordingFormatLabel` |
| `settings_recording_format` | `settingsRecordingFormat` |
| `settings_recording_save_location_holder` | `settingsRecordingSaveLocationHolder` |
| `settings_recording_save_location_label` | `settingsRecordingSaveLocationLabel` |
| `settings_recording_save_location` | `settingsRecordingSaveLocation` |
| `settings_recording_custom_path_holder` | `settingsRecordingCustomPathHolder` |
| `settings_recording_custom_path_label` | `settingsRecordingCustomPathLabel` |
| `settings_recording_custom_path` | `settingsRecordingCustomPath` |
| `settings_recording_current_path_holder` | `settingsRecordingCurrentPathHolder` |
| `settings_recording_current_path_label` | `settingsRecordingCurrentPathLabel` |
| `settings_recording_current_path` | `settingsRecordingCurrentPath` |
| `settings_file_naming_label` | `settingsFileNamingLabel` |
| `settings_file_naming_card` | `settingsFileNamingHolder` |
| `settings_recording_file_template_holder` | `settingsRecordingFileTemplateHolder` |
| `settings_recording_file_template_label` | `settingsRecordingFileTemplateLabel` |
| `settings_recording_file_template` | `settingsRecordingFileTemplate` |
| `settings_recording_preview_holder` | `settingsRecordingPreviewHolder` |
| `settings_recording_preview_label` | `settingsRecordingPreviewLabel` |
| `settings_recording_preview` | `settingsRecordingPreview` |
| `settings_actions_label` | `settingsActionsLabel` |
| `settings_actions_card` | `settingsActionsHolder` |
| `settings_recording_open_folder_holder` | `settingsRecordingOpenFolderHolder` |
| `settings_recording_open_folder_label` | `settingsRecordingOpenFolderLabel` |

## Layout Structure Consistency

Both activities now follow the same structure:

```xml
<eightbitlab.com.blurview.BlurTarget>
    <CoordinatorLayout>
        <AppBarLayout>
            <MaterialToolbar />
        </AppBarLayout>
        
        <NestedScrollView>
            <LinearLayout id="...Holder">
                <!-- Section Label -->
                <TextView id="settingsSectionLabel" />
                
                <!-- Section Card -->
                <CardView id="settingsSectionHolder">
                    <LinearLayout id="settingsSectionWrapper">
                        <!-- Settings Items -->
                        <RelativeLayout id="settingsItemHolder" />
                    </LinearLayout>
                </CardView>
            </LinearLayout>
        </NestedScrollView>
    </CoordinatorLayout>
</eightbitlab.com.blurview.BlurTarget>
```

## Benefits

✅ **Consistent Naming**: All IDs follow the same camelCase convention
✅ **Easier Navigation**: Developers can predict ID names across settings screens
✅ **Code Maintainability**: Consistent patterns make code easier to understand
✅ **Professional**: Unified styling across the application
✅ **No Breaking Changes**: Kotlin code already used camelCase, so no code changes needed

## Verification

The Kotlin code (`RecordingSettingsActivity.kt`) already used camelCase for binding references, so no code changes were required:

```kotlin
binding.settingsRecordingEnabledHolder.setOnClickListener { ... }
binding.settingsRecordingAutoRule.text = ruleName
binding.settingsRecordingFormat.text = formatName
// ... etc
```

All binding references now correctly match the updated layout IDs.

## Files Modified

1. `app/src/main/res/layout/activity_recording_settings.xml`
   - Updated all ID naming from snake_case to camelCase
   - Added wrapper LinearLayout IDs
   - Fixed color references
   - Maintained all functionality

2. `app/src/main/kotlin/com/android/dialer/activities/RecordingSettingsActivity.kt`
   - No changes needed (already using camelCase)

## Testing

After these changes, verify:
- [ ] Recording settings screen opens without crashes
- [ ] All click handlers work correctly
- [ ] UI elements display properly
- [ ] Settings are saved and loaded correctly
- [ ] BlurTarget effects work as expected

