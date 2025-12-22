# Call Recording File Naming and Location

## Overview

You can now customize where recordings are saved and how they're named, just like BCR! This gives you full control over file organization.

## ✨ New Features

### 📂 Save Location Options

Choose where to save your recordings:

1. **App Storage** (Default)
   - Location: `/storage/emulated/0/Android/data/com.android.dialer/files/Recordings/`
   - Deleted when app is uninstalled
   - No extra permissions needed

2. **Music**
   - Location: `/storage/emulated/0/Music/Call Recordings/`
   - Survives app uninstall
   - Accessible via Music apps
   - Requires storage permission

3. **Documents**
   - Location: `/storage/emulated/0/Documents/Call Recordings/`
   - Organized with documents
   - Easy to find and share
   - Requires storage permission

4. **Recordings** (Android 12+)
   - Location: `/storage/emulated/0/Recordings/Call Recordings/`
   - Dedicated recordings folder
   - Requires Android 12 or higher
   - Requires storage permission

5. **Custom**
   - Location: Your choice!
   - Full path control
   - Can be on SD card
   - Requires storage permission

### 📝 File Name Templates

Customize file names using variables:

| Variable | Description | Example |
|----------|-------------|---------|
| `{timestamp}` | Full timestamp | `20231221_143025.123+0000` |
| `{date}` | Date only | `20231221` |
| `{time}` | Time only | `143025` |
| `{direction}` | Call direction | `in` or `out` |
| `{phone_number}` | Phone number | `1234567890` |
| `{caller_name}` | Contact name | `John_Doe` |
| `{sim_slot}` | SIM card slot | `1` or `2` |

**Default Template**: `{timestamp}_{direction}_{phone_number}`

**Example Output**: `20231221_143025.123+0000_out_1234567890.wav`

## 🚀 How to Use

### Set Save Location

```kotlin
// Option 1: App Storage (default)
config.recordingSaveLocation = RecordingFileManager.LOCATION_APP_FILES

// Option 2: Music folder
config.recordingSaveLocation = RecordingFileManager.LOCATION_MUSIC

// Option 3: Documents folder
config.recordingSaveLocation = RecordingFileManager.LOCATION_DOCUMENTS

// Option 4: Recordings folder (Android 12+)
config.recordingSaveLocation = RecordingFileManager.LOCATION_RECORDINGS

// Option 5: Custom location
config.recordingSaveLocation = RecordingFileManager.LOCATION_CUSTOM
config.recordingCustomPath = "/storage/emulated/0/MyRecordings"
```

### Set File Name Template

```kotlin
// Default BCR-style
config.recordingFileNameTemplate = "{timestamp}_{direction}_{phone_number}"

// Date and time separate
config.recordingFileNameTemplate = "{date}_{time}_{direction}_{phone_number}"

// With caller name
config.recordingFileNameTemplate = "{date}_{caller_name}_{direction}"

// Simple timestamp
config.recordingFileNameTemplate = "{timestamp}"

// With SIM slot
config.recordingFileNameTemplate = "{date}_{time}_SIM{sim_slot}_{phone_number}"
```

### Get Example File Name

```kotlin
val fileManager = RecordingFileManager(context)

// Preview how files will be named
val example = fileManager.getExampleFileName(CallRecorder.OutputFormat.WAV)
// Result: "20231221_143025.123+0000_out_1234567890.wav"
```

### Get Available Locations

```kotlin
val fileManager = RecordingFileManager(context)

val locations = fileManager.getAvailableLocations()
for (location in locations) {
    println("${location.name}: ${location.path}")
}
```

## 📋 Template Examples

### Example 1: BCR Default Style
```kotlin
Template: "{timestamp}_{direction}_{phone_number}"
Result:   "20231221_143025.123+0000_out_1234567890.wav"
```

### Example 2: Date-Time-Number
```kotlin
Template: "{date}_{time}_{phone_number}"
Result:   "20231221_143025_1234567890.wav"
```

### Example 3: With Contact Name
```kotlin
Template: "{date}_{caller_name}_({phone_number})"
Result:   "20231221_John_Doe_(1234567890).wav"
```

### Example 4: Direction First
```kotlin
Template: "{direction}_{date}_{phone_number}"
Result:   "out_20231221_1234567890.wav"
```

### Example 5: Detailed Format
```kotlin
Template: "Call_{direction}_{date}_{time}_{caller_name}_SIM{sim_slot}"
Result:   "Call_out_20231221_143025_John_Doe_SIM1.wav"
```

### Example 6: Simple Timestamp
```kotlin
Template: "{timestamp}"
Result:   "20231221_143025.123+0000.wav"
```

### Example 7: Custom Prefix
```kotlin
Template: "Recording_{date}_{phone_number}"
Result:   "Recording_20231221_1234567890.wav"
```

## 🔧 Using RecordingFileManager

The `RecordingFileManager` class handles all file operations:

```kotlin
val fileManager = RecordingFileManager(context)

// Get save directory
val directory = fileManager.getSaveDirectory()
println("Saving to: ${directory.absolutePath}")

// Generate file name
val fileName = fileManager.generateFileName(
    call = call,
    format = CallRecorder.OutputFormat.WAV,
    callerName = "John Doe"  // Optional
)
println("File name: $fileName")

// Create complete output file
val outputFile = fileManager.createOutputFile(
    call = call,
    format = CallRecorder.OutputFormat.WAV,
    callerName = "John Doe"  // Optional
)
println("Full path: ${outputFile.absolutePath}")

// Get template variables info
val variables = fileManager.getTemplateVariables()
for (variable in variables) {
    println("${variable.variable}: ${variable.name} (e.g., ${variable.example})")
}

// Validate template
val isValid = fileManager.isValidTemplate("{date}_{phone_number}")
println("Template valid: $isValid")
```

## 🎨 UI Integration Examples

### Example: Location Picker Dialog

```kotlin
fun showLocationPicker(context: Context) {
    val fileManager = RecordingFileManager(context)
    val locations = fileManager.getAvailableLocations()
    
    val names = locations.map { it.name }.toTypedArray()
    val currentLocation = context.config.recordingSaveLocation
    
    AlertDialog.Builder(context)
        .setTitle("Save Location")
        .setSingleChoiceItems(names, currentLocation) { dialog, which ->
            context.config.recordingSaveLocation = locations[which].id
            
            // If custom location selected, show path picker
            if (locations[which].id == RecordingFileManager.LOCATION_CUSTOM) {
                showCustomPathPicker(context)
            }
            
            dialog.dismiss()
        }
        .show()
}
```

### Example: Template Editor Dialog

```kotlin
fun showTemplateEditor(context: Context) {
    val fileManager = RecordingFileManager(context)
    val currentTemplate = context.config.recordingFileNameTemplate
    
    val input = EditText(context).apply {
        setText(currentTemplate)
        hint = "Enter template"
    }
    
    val variables = fileManager.getTemplateVariables()
    val variablesText = variables.joinToString("\n") {
        "${it.variable} - ${it.name}"
    }
    
    AlertDialog.Builder(context)
        .setTitle("File Name Template")
        .setMessage("Available variables:\n$variablesText")
        .setView(input)
        .setPositiveButton("Save") { _, _ ->
            val template = input.text.toString()
            if (fileManager.isValidTemplate(template)) {
                context.config.recordingFileNameTemplate = template
                
                // Show example
                val example = fileManager.getExampleFileName()
                Toast.makeText(context, "Example: $example", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Invalid template", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .setNeutralButton("Reset to Default") { _, _ ->
            context.config.recordingFileNameTemplate = 
                RecordingFileManager.DEFAULT_TEMPLATE
        }
        .show()
}
```

### Example: Template Preview

```kotlin
fun showTemplatePreview(context: Context, template: String) {
    val fileManager = RecordingFileManager(context)
    
    // Temporarily set template to get preview
    val oldTemplate = context.config.recordingFileNameTemplate
    context.config.recordingFileNameTemplate = template
    
    val example = fileManager.getExampleFileName()
    
    // Restore old template
    context.config.recordingFileNameTemplate = oldTemplate
    
    // Show preview
    Toast.makeText(context, "Preview: $example", Toast.LENGTH_LONG).show()
}
```

## 📱 Settings Screen Integration

Add these preferences to your settings:

```xml
<!-- Recording Location -->
<ListPreference
    android:key="recording_save_location"
    android:title="Save Location"
    android:summary="%s"
    android:entries="@array/recording_locations"
    android:entryValues="@array/recording_location_values"
    android:defaultValue="0"
    android:dependency="call_recording_enabled" />

<!-- File Name Template -->
<EditTextPreference
    android:key="recording_file_name_template"
    android:title="File Name Template"
    android:summary="%s"
    android:defaultValue="{timestamp}_{direction}_{phone_number}"
    android:inputType="text"
    android:dependency="call_recording_enabled" />

<!-- Preview Button -->
<Preference
    android:key="recording_file_preview"
    android:title="Preview File Name"
    android:summary="See how files will be named" />
```

**arrays.xml**:
```xml
<string-array name="recording_locations">
    <item>App Storage</item>
    <item>Music</item>
    <item>Documents</item>
    <item>Recordings (Android 12+)</item>
    <item>Custom</item>
</string-array>

<integer-array name="recording_location_values">
    <item>0</item>
    <item>1</item>
    <item>2</item>
    <item>3</item>
    <item>4</item>
</integer-array>
```

## 🔒 Permissions

### App Storage (Location 0)
- ✅ No extra permissions needed
- ✅ Works out of the box

### Other Locations (1, 2, 3, 4)
- ⚠️ Requires storage permissions
- Add to AndroidManifest.xml:

```xml
<!-- For Android 10 and below -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="29" />

<!-- For Android 11+ -->
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

### Request Permissions

```kotlin
// For Android 11+
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    if (!Environment.isExternalStorageManager()) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.parse("package:${context.packageName}")
        startActivity(intent)
    }
} else {
    // For Android 10 and below
    ActivityCompat.requestPermissions(
        activity,
        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
        REQUEST_CODE
    )
}
```

## 🎯 File Name Sanitization

The system automatically sanitizes file names:

### Automatic Cleaning

- Removes invalid characters: `\ / : * ? " < > |`
- Replaces spaces with underscores
- Removes double underscores
- Trims leading/trailing underscores
- Limits length to 50 characters per variable
- Ensures file name is never empty

### Examples

| Input | Output |
|-------|--------|
| `John Doe` | `John_Doe` |
| `+1 (234) 567-8900` | `1234567890` |
| `Company: Inc.` | `Company_Inc` |
| `Test//File` | `TestFile` |

## 🔍 File Conflict Handling

If a file already exists with the same name:

```
Original:  20231221_143025.123+0000_out_1234567890.wav
Conflict:  20231221_143025.123+0000_out_1234567890_1.wav
Conflict:  20231221_143025.123+0000_out_1234567890_2.wav
...
```

The system automatically appends a number to avoid overwriting.

## 📊 Comparison with BCR

| Feature | BCR | Your App |
|---------|-----|----------|
| Custom save location | ✅ | ✅ |
| Music folder | ✅ | ✅ |
| Documents folder | ✅ | ✅ |
| Recordings folder | ✅ | ✅ |
| Custom path | ✅ | ✅ |
| File name templates | ✅ | ✅ |
| Template variables | ✅ | ✅ |
| File sanitization | ✅ | ✅ |
| Conflict handling | ✅ | ✅ |
| Preview file names | ✅ | ✅ |

**Result**: 100% feature parity with BCR! 🎉

## 🐛 Troubleshooting

### Files Not Saving

**Problem**: Recordings aren't being saved

**Solutions**:
1. Check storage permissions
2. Verify directory exists and is writable
3. Check available storage space
4. Try switching to App Storage location

```kotlin
// Check if directory is writable
val directory = fileManager.getSaveDirectory()
val canWrite = directory.canWrite()
println("Can write: $canWrite")
```

### Invalid Template Error

**Problem**: Template validation fails

**Solutions**:
1. Remove invalid characters: `\ / : * ? " < > |`
2. Ensure template isn't empty
3. Use only allowed variables
4. Test with default template first

```kotlin
// Test template
val isValid = fileManager.isValidTemplate(template)
if (!isValid) {
    // Reset to default
    config.recordingFileNameTemplate = RecordingFileManager.DEFAULT_TEMPLATE
}
```

### Custom Path Not Working

**Problem**: Custom location doesn't work

**Solutions**:
1. Ensure path exists or can be created
2. Check write permissions
3. Use absolute paths (start with `/`)
4. Avoid restricted system directories

```kotlin
// Validate custom path
val customPath = "/storage/emulated/0/MyRecordings"
val directory = File(customPath)

if (!directory.exists()) {
    val created = directory.mkdirs()
    println("Directory created: $created")
}

if (directory.canWrite()) {
    config.recordingCustomPath = customPath
    config.recordingSaveLocation = RecordingFileManager.LOCATION_CUSTOM
}
```

## 💡 Best Practices

### 1. Use App Storage for Privacy
```kotlin
config.recordingSaveLocation = RecordingFileManager.LOCATION_APP_FILES
```
- Files deleted when app uninstalled
- No extra permissions needed
- Best for sensitive recordings

### 2. Use Music/Documents for Sharing
```kotlin
config.recordingSaveLocation = RecordingFileManager.LOCATION_MUSIC
```
- Files survive app uninstall
- Easy to share and backup
- Accessible from other apps

### 3. Include Date in Template
```kotlin
config.recordingFileNameTemplate = "{date}_{time}_{phone_number}"
```
- Easy to sort chronologically
- Quick to find by date
- Good for archiving

### 4. Add Caller Name for Organization
```kotlin
config.recordingFileNameTemplate = "{date}_{caller_name}_{direction}"
```
- Human-readable file names
- Easy to identify calls
- Better for manual organization

### 5. Use Timestamp for Uniqueness
```kotlin
config.recordingFileNameTemplate = "{timestamp}_{direction}_{phone_number}"
```
- Guaranteed unique names
- Millisecond precision
- Prevents conflicts

## 📚 Complete Example

```kotlin
class RecordingSettingsActivity : AppCompatActivity() {
    
    private lateinit var fileManager: RecordingFileManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        fileManager = RecordingFileManager(this)
        
        setupLocationPicker()
        setupTemplateEditor()
        showCurrentSettings()
    }
    
    private fun setupLocationPicker() {
        val locations = fileManager.getAvailableLocations()
        
        locationButton.setOnClickListener {
            val names = locations.map { it.name }.toTypedArray()
            
            AlertDialog.Builder(this)
                .setTitle("Save Location")
                .setItems(names) { _, which ->
                    config.recordingSaveLocation = locations[which].id
                    updateLocationDisplay()
                }
                .show()
        }
    }
    
    private fun setupTemplateEditor() {
        templateButton.setOnClickListener {
            showTemplateEditor()
        }
        
        previewButton.setOnClickListener {
            val example = fileManager.getExampleFileName()
            Toast.makeText(this, "Example: $example", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showTemplateEditor() {
        val variables = fileManager.getTemplateVariables()
        val variablesText = variables.joinToString("\n") {
            "${it.variable} → ${it.example}"
        }
        
        val input = EditText(this).apply {
            setText(config.recordingFileNameTemplate)
        }
        
        AlertDialog.Builder(this)
            .setTitle("File Name Template")
            .setMessage(variablesText)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val template = input.text.toString()
                if (fileManager.isValidTemplate(template)) {
                    config.recordingFileNameTemplate = template
                    updateTemplateDisplay()
                }
            }
            .show()
    }
    
    private fun showCurrentSettings() {
        val location = fileManager.getSaveDirectory()
        val example = fileManager.getExampleFileName()
        
        locationText.text = "Location: ${location.absolutePath}"
        templateText.text = "Template: ${config.recordingFileNameTemplate}"
        exampleText.text = "Example: $example"
    }
}
```

## ✅ Summary

You now have full control over:

✅ **Where recordings are saved** (5 location options)  
✅ **How files are named** (customizable templates)  
✅ **What information is included** (7 variables available)  
✅ **File organization** (flexible naming patterns)  

Just like BCR, but integrated into your app! 🎉

---

For integration examples and UI code, see the examples above.
For technical details, see `RecordingFileManager.kt`.

