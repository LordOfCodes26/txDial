package com.android.dialer.activities

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import eightbitlab.com.blurview.BlurTarget
import com.android.dialer.R
import com.android.dialer.databinding.ActivityRecordingSettingsBinding
import com.android.dialer.extensions.config
import com.android.dialer.helpers.RECORDING_RULE_ALL_CALLS
import com.android.dialer.helpers.RECORDING_RULE_KNOWN_CONTACTS
import com.android.dialer.helpers.RECORDING_RULE_NONE
import com.android.dialer.helpers.RECORDING_RULE_UNKNOWN_NUMBERS
import com.android.dialer.recording.CallRecorder
import com.android.dialer.recording.RecordingFileManager
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.NavigationIcon
import com.goodwy.commons.helpers.PERMISSION_WRITE_STORAGE
import com.goodwy.commons.helpers.ensureBackgroundThread

class RecordingSettingsActivity : SimpleActivity() {
    
    private val binding by viewBinding(ActivityRecordingSettingsBinding::inflate)
    private lateinit var fileManager: RecordingFileManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        
        fileManager = RecordingFileManager(this)
        
        binding.apply {
            setupMaterialScrollListener(recordingSettingsNestedScrollview, recordingSettingsAppbar)
        }
        
        setupOptionsMenu()
        setupUI()
        updateUI()
    }
    
    override fun onResume() {
        super.onResume()
//        setupToolbar(binding.recordingSettingsToolbar)
        setupTopAppBar(binding.recordingSettingsAppbar, NavigationIcon.Arrow)

        updateUI()
    }
    
    private fun setupOptionsMenu() {
        binding.recordingSettingsToolbar.inflateMenu(R.menu.menu_recording_settings)
        binding.recordingSettingsToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.help -> {
                    showHelp()
                    true
                }
                else -> false
            }
        }
        updateMenuItemColors(binding.recordingSettingsToolbar.menu)
    }
    
    private fun setupUI() {
        // Enable/Disable Recording
        binding.settingsRecordingEnabledHolder.setOnClickListener {
            config.callRecordingEnabled = !config.callRecordingEnabled
            updateUI()
        }
        
        // Auto-recording Rule
        binding.settingsRecordingAutoRuleHolder.setOnClickListener {
            showAutoRecordingRulePicker()
        }
        
        // Audio Format
        binding.settingsRecordingFormatHolder.setOnClickListener {
            showFormatPicker()
        }
        
        // Save Location
        binding.settingsRecordingSaveLocationHolder.setOnClickListener {
            showLocationPicker()
        }
        
        // Custom Path (only if custom location is selected)
        binding.settingsRecordingCustomPathHolder.setOnClickListener {
            if (config.callRecordingEnabled && config.recordingSaveLocation == RecordingFileManager.LOCATION_CUSTOM) {
                showCustomPathPicker()
            } else if (!config.callRecordingEnabled) {
                toast(R.string.enable_recording_first)
            }
        }
        
        // File Name Template
        binding.settingsRecordingFileTemplateHolder.setOnClickListener {
            showTemplateEditor()
        }
        
        // Preview
        binding.settingsRecordingPreviewHolder.setOnClickListener {
            showPreview()
        }
        
        // Open Recordings Folder
        binding.settingsRecordingOpenFolderHolder.setOnClickListener {
            openRecordingsFolder()
        }
    }
    
    private fun updateUI() {
        // Recording Enabled
        val enabled = config.callRecordingEnabled
        binding.settingsRecordingEnabledSwitch.isChecked = enabled
        
        // Auto-recording Rule
        val ruleName = when (config.callRecordingAutoRule) {
            RECORDING_RULE_NONE -> getString(R.string.recording_rule_none)
            RECORDING_RULE_ALL_CALLS -> getString(R.string.recording_rule_all)
            RECORDING_RULE_UNKNOWN_NUMBERS -> getString(R.string.recording_rule_unknown)
            RECORDING_RULE_KNOWN_CONTACTS -> getString(R.string.recording_rule_known)
            else -> getString(R.string.recording_rule_all)
        }
        binding.settingsRecordingAutoRule.text = ruleName
        
        // Audio Format
        val formatName = when (config.callRecordingFormat) {
            0 -> getString(R.string.format_wav)
            1 -> getString(R.string.format_opus)
            2 -> getString(R.string.format_aac)
            3 -> getString(R.string.format_flac)
            else -> getString(R.string.format_wav)
        }
        binding.settingsRecordingFormat.text = formatName
        
        // Save Location
        val locationName = when (config.recordingSaveLocation) {
            RecordingFileManager.LOCATION_APP_FILES -> getString(R.string.location_app_storage)
            RecordingFileManager.LOCATION_MUSIC -> getString(R.string.location_music)
            RecordingFileManager.LOCATION_DOCUMENTS -> getString(R.string.location_documents)
            RecordingFileManager.LOCATION_RECORDINGS -> getString(R.string.location_recordings)
            RecordingFileManager.LOCATION_CUSTOM -> getString(R.string.location_custom)
            else -> getString(R.string.location_app_storage)
        }
        binding.settingsRecordingSaveLocation.text = locationName
        
        // Custom Path
        val isCustomLocation = config.recordingSaveLocation == RecordingFileManager.LOCATION_CUSTOM
        binding.settingsRecordingCustomPathHolder.beVisibleIf(isCustomLocation && enabled)
        
        if (isCustomLocation && enabled) {
            val path = config.recordingCustomPath.ifEmpty { getString(R.string.not_set) }
            binding.settingsRecordingCustomPath.text = path
        }
        
        // File Template
        binding.settingsRecordingFileTemplate.text = config.recordingFileNameTemplate
        
        // Preview
        val example = fileManager.getExampleFileName(getCurrentFormat())
        binding.settingsRecordingPreview.text = example
        
        // Current location path
        val directory = fileManager.getSaveDirectory()
        binding.settingsRecordingCurrentPath.text = directory.absolutePath
        
        // Enable/disable dependent settings
        val disabledAlpha = 0.5f
        val enabledAlpha = 1f
        
        // Update alpha and clickable state
        binding.settingsRecordingAutoRuleHolder.apply {
            alpha = if (enabled) enabledAlpha else disabledAlpha
            isClickable = enabled
            isEnabled = enabled
        }
        
        binding.settingsRecordingFormatHolder.apply {
            alpha = if (enabled) enabledAlpha else disabledAlpha
            isClickable = enabled
            isEnabled = enabled
        }
        
        binding.settingsRecordingSaveLocationHolder.apply {
            alpha = if (enabled) enabledAlpha else disabledAlpha
            isClickable = enabled
            isEnabled = enabled
        }
        
        binding.settingsRecordingCustomPathHolder.apply {
            alpha = if (enabled) enabledAlpha else disabledAlpha
            isClickable = enabled && config.recordingSaveLocation == RecordingFileManager.LOCATION_CUSTOM
            isEnabled = enabled && config.recordingSaveLocation == RecordingFileManager.LOCATION_CUSTOM
        }
        
        binding.settingsRecordingFileTemplateHolder.apply {
            alpha = if (enabled) enabledAlpha else disabledAlpha
            isClickable = enabled
            isEnabled = enabled
        }
        
        binding.settingsRecordingPreviewHolder.apply {
            alpha = if (enabled) enabledAlpha else disabledAlpha
            isClickable = enabled
            isEnabled = enabled
        }
        
        binding.settingsRecordingOpenFolderHolder.apply {
            alpha = if (enabled) enabledAlpha else disabledAlpha
            isClickable = enabled
            isEnabled = enabled
        }
    }
    
    private fun getCurrentFormat(): CallRecorder.OutputFormat {
        return when (config.callRecordingFormat) {
            0 -> CallRecorder.OutputFormat.WAV
            1 -> CallRecorder.OutputFormat.OGG_OPUS
            2 -> CallRecorder.OutputFormat.M4A_AAC
            3 -> CallRecorder.OutputFormat.FLAC
            else -> CallRecorder.OutputFormat.WAV
        }
    }
    
    private fun showAutoRecordingRulePicker() {
        if (!config.callRecordingEnabled) {
            toast(R.string.enable_recording_first)
            return
        }
        
        val items = arrayListOf(
            RadioItem(RECORDING_RULE_NONE, getString(R.string.recording_rule_none)),
            RadioItem(RECORDING_RULE_ALL_CALLS, getString(R.string.recording_rule_all)),
            RadioItem(RECORDING_RULE_UNKNOWN_NUMBERS, getString(R.string.recording_rule_unknown)),
            RadioItem(RECORDING_RULE_KNOWN_CONTACTS, getString(R.string.recording_rule_known))
        )
        
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
        
        RadioGroupDialog(
            this,
            items,
            config.callRecordingAutoRule,
            R.string.auto_recording_rule,
            blurTarget = blurTarget
        ) {
            config.callRecordingAutoRule = it as Int
            updateUI()
        }
    }
    
    private fun showFormatPicker() {
        if (!config.callRecordingEnabled) {
            toast(R.string.enable_recording_first)
            return
        }
        
        val items = arrayListOf(
            RadioItem(0, getString(R.string.format_wav_desc)),
            RadioItem(1, getString(R.string.format_opus_desc)),
            RadioItem(2, getString(R.string.format_aac_desc)),
            RadioItem(3, getString(R.string.format_flac_desc))
        )
        
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
        
        RadioGroupDialog(
            this,
            items,
            config.callRecordingFormat,
            R.string.recording_format,
            blurTarget = blurTarget
        ) {
            config.callRecordingFormat = it as Int
            updateUI()
        }
    }
    
    private fun showLocationPicker() {
        if (!config.callRecordingEnabled) {
            toast(R.string.enable_recording_first)
            return
        }
        
        val locations = fileManager.getAvailableLocations()
        val items = locations.mapIndexed { index, location ->
            RadioItem(index, location.name)
        } as ArrayList<RadioItem>
        
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
        
        RadioGroupDialog(
            this,
            items,
            config.recordingSaveLocation,
            R.string.save_location,
            blurTarget = blurTarget
        ) { selected ->
            val which = selected as Int
            val selectedLocation = locations[which]
            
            // Check permissions for non-app storage locations
            if (selectedLocation.id != RecordingFileManager.LOCATION_APP_FILES) {
                if (!hasStoragePermission()) {
                    requestStoragePermission {
                        if (it) {
                            config.recordingSaveLocation = selectedLocation.id
                            if (selectedLocation.id == RecordingFileManager.LOCATION_CUSTOM) {
                                showCustomPathPicker()
                            }
                            updateUI()
                        }
                    }
                } else {
                    config.recordingSaveLocation = selectedLocation.id
                    if (selectedLocation.id == RecordingFileManager.LOCATION_CUSTOM) {
                        showCustomPathPicker()
                    }
                    updateUI()
                }
            } else {
                config.recordingSaveLocation = selectedLocation.id
                updateUI()
            }
        }
    }
    
    private fun showCustomPathPicker() {
        val input = EditText(this).apply {
            setText(config.recordingCustomPath)
            hint = "/storage/emulated/0/MyRecordings"
            setSingleLine()
        }
        
        getAlertDialogBuilder()
            .setTitle(R.string.custom_path)
            .setMessage(R.string.custom_path_description)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    config.recordingCustomPath = path
                    updateUI()
                } else {
                    toast(R.string.path_cannot_be_empty)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .apply { setupDialogStuff(input, this, R.string.custom_path) }
    }
    
    private fun showTemplateEditor() {
        if (!config.callRecordingEnabled) {
            toast(R.string.enable_recording_first)
            return
        }
        
        val variables = fileManager.getTemplateVariables()
        val variablesText = variables.joinToString("\n") {
            "${it.variable} → ${it.example}"
        }
        
        val input = EditText(this).apply {
            setText(config.recordingFileNameTemplate)
            hint = RecordingFileManager.DEFAULT_TEMPLATE
            setSingleLine()
        }
        
        getAlertDialogBuilder()
            .setPositiveButton(R.string.ok) { _, _ ->
                val template = input.text.toString().trim()
                if (template.isNotEmpty()) {
                    if (fileManager.isValidTemplate(template)) {
                        config.recordingFileNameTemplate = template
                        updateUI()
                        showPreview()
                    } else {
                        toast(R.string.invalid_template)
                    }
                } else {
                    toast(R.string.template_cannot_be_empty)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset_to_default) { _, _ ->
                config.recordingFileNameTemplate = RecordingFileManager.DEFAULT_TEMPLATE
                updateUI()
            }
            .apply { 
                val message = getString(R.string.available_variables) + "\n\n" + variablesText
                setupDialogStuff(input, this, R.string.file_name_template, message) 
            }
    }
    
    private fun showPreview() {
        if (!config.callRecordingEnabled) {
            toast(R.string.enable_recording_first)
            return
        }
        
        val example = fileManager.getExampleFileName(getCurrentFormat())
        val directory = fileManager.getSaveDirectory()
        
        val message = getString(R.string.preview_file_name) + "\n\n" +
                getString(R.string.file_name_label) + " $example\n\n" +
                getString(R.string.save_location_label) + " ${directory.absolutePath}"

        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
        ConfirmationDialog(
            this,
            message = message,
            positive = R.string.ok,
            negative = 0,
            cancelOnTouchOutside = false,
            blurTarget = blurTarget
        ) {}
    }
    
    private fun openRecordingsFolder() {
        if (!config.callRecordingEnabled) {
            toast(R.string.enable_recording_first)
            return
        }
        
        ensureBackgroundThread {
            try {
                val directory = fileManager.getSaveDirectory()
                
                // Try multiple methods to open the folder
                val intents = mutableListOf<Intent>()
                
                // Method 1: Standard file manager intent (works on most devices)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    // Android 6 and below: direct file URI
                    intents.add(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse("file://${directory.absolutePath}"), "resource/folder")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
                
                // Method 2: Generic file manager picker
                intents.add(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                })
                
                // Method 3: Try to open with DocumentsUI (Android 5.0+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    intents.add(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A${directory.name}"), "*/*")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
                
                var opened = false
                for (intent in intents) {
                    try {
                        if (intent.resolveActivity(packageManager) != null) {
                            runOnUiThread {
                                startActivity(intent)
                            }
                            opened = true
                            break
                        }
                    } catch (e: Exception) {
                        // Try next method
                        continue
                    }
                }
                
                if (!opened) {
                    runOnUiThread {
                        // Show directory path as fallback
                        val message = getString(R.string.cannot_open_folder) + "\n\n" + 
                                getString(R.string.save_location_label) + " " + directory.absolutePath
                        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                        ConfirmationDialog(
                            this,
                            message = message,
                            positive = R.string.ok,
                            negative = 0,
                            cancelOnTouchOutside = true,
                            blurTarget = blurTarget
                        ) {}
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast(R.string.cannot_open_folder)
                }
            }
        }
    }
    
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            hasPermission(PERMISSION_WRITE_STORAGE)
        }
    }
    
    private fun requestStoragePermission(callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: Need MANAGE_EXTERNAL_STORAGE permission
            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
            ConfirmationDialog(
                this,
                messageId = R.string.storage_permission_description,
                positive = R.string.grant_permission,
                negative = R.string.cancel,
                blurTarget = blurTarget
            ) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    storagePermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    callback(false)
                }
            }
        } else {
            // Android 10 and below
            handlePermission(PERMISSION_WRITE_STORAGE) { granted ->
                callback(granted)
            }
        }
    }
    
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                // Permission granted
                updateUI()
            } else {
                toast(R.string.storage_permission_denied)
            }
        }
    }
    
    
    private fun showHelp() {
        val helpText = """
            ${getString(R.string.recording_help_text)}
            
            ${getString(R.string.recording_help_requirements)}
            
            ${getString(R.string.recording_help_variables)}
        """.trimIndent()
        
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
        ConfirmationDialog(
            this,
            message = helpText,
            positive = R.string.ok,
            negative = 0,
            cancelOnTouchOutside = false,
            blurTarget = blurTarget
        ) {}
    }
}

