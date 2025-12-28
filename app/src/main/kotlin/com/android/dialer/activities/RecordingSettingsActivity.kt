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
import com.goodwy.commons.dialogs.FilePickerDialog
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.NavigationIcon
import com.goodwy.commons.helpers.PERMISSION_WRITE_STORAGE
import com.goodwy.commons.helpers.ensureBackgroundThread
import androidx.core.net.toUri

class RecordingSettingsActivity : SimpleActivity() {
    
    private val binding by viewBinding(ActivityRecordingSettingsBinding::inflate)
    
    // Cache blur target to avoid repeated findViewById calls
    private val blurTarget: BlurTarget by lazy {
        findViewById<BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
    }
    
    private lateinit var fileManager: RecordingFileManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        
        fileManager = RecordingFileManager(this)
        
        binding.apply {
            setupMaterialScrollListener(recordingSettingsNestedScrollview, recordingSettingsAppbar)
        }
        
//        setupOptionsMenu()
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
        val currentPath = if (config.recordingCustomPath.isNotEmpty()) {
            config.recordingCustomPath
        } else {
            internalStoragePath
        }
        
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
        FilePickerDialog(
            activity = this,
            currPath = currentPath,
            pickFile = false, // false = pick folder, true = pick file
            showFAB = true,
            titleText = R.string.custom_path,
            useAccentColor = true,
            blurTarget = blurTarget
        ) { pickedPath ->
            config.recordingCustomPath = pickedPath
            updateUI()
        }
    }
    
    private fun openRecordingsFolder() {
        if (!config.callRecordingEnabled) {
            toast(R.string.enable_recording_first)
            return
        }
        
        // Check if we need storage permissions for non-app storage locations
        val location = config.recordingSaveLocation
        if (location != RecordingFileManager.LOCATION_APP_FILES && !hasStoragePermission()) {
            requestStoragePermission { granted ->
                if (granted) {
                    openRecordingsFolderInternal()
                } else {
                    toast(R.string.storage_permission_denied)
                }
            }
            return
        }
        
        openRecordingsFolderInternal()
    }
    
    private fun openRecordingsFolderInternal() {
        try {
            val directory = fileManager.getSaveDirectory()
            
            // Ensure directory exists
            if (!directory.exists()) {
                val created = directory.mkdirs()
                if (!created) {
                    toast(R.string.cannot_open_folder)
                    return
                }
            }
            
            val path = directory.absolutePath
            val location = config.recordingSaveLocation
            
            // Try multiple methods to open the folder
            val methods = listOf(
                // Method 1: Try SAF-only root handling
                {
                    if (isSAFOnlyRoot(path)) {
                        try {
                            createAndroidDataOrObbUri(path)
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                },
                // Method 2: Try OTG paths
                {
                    if (isPathOnOTG(path)) {
                        try {
                            val uriString: String = path.getPublicUri(this@RecordingSettingsActivity) as String
                            if (uriString.isNotEmpty()) {
                                Uri.parse(uriString)
                            } else null
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                },
                // Method 3: Try SAF SDK 30+ paths (for paths that need SAF)
                {
                    if (isAccessibleWithSAFSdk30(path)) {
                        try {
                            createDocumentUriUsingFirstParentTreeUri(path)
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                },
                // Method 4: For custom paths, try createDocumentUriFromRootTree first
                {
                    if (location == RecordingFileManager.LOCATION_CUSTOM) {
                        try {
                            createDocumentUriFromRootTree(path)
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                },
                // Method 5: Try ensurePublicUri (works for most standard paths)
                {
                    try {
                        ensurePublicUri(path, packageName)
                    } catch (e: Exception) {
                        null
                    }
                },
                // Method 6: Try createDocumentUriFromRootTree as fallback (for all paths)
                {
                    try {
                        createDocumentUriFromRootTree(path)
                    } catch (e: Exception) {
                        null
                    }
                },
                // Method 7: For custom paths, try getPublicUri extension (for SD card/OTG)
                {
                    if (location == RecordingFileManager.LOCATION_CUSTOM) {
                        try {
                            val uriString: String = path.getPublicUri(this@RecordingSettingsActivity) as String
                            if (uriString.isNotEmpty() && uriString.startsWith("content://")) {
                                val uri = uriString.toUri()
                                // Validate URI doesn't point to wrong location
                                val uriStr = uri.toString()
                                if (uriStr.contains("download", ignoreCase = true) && 
                                    !path.contains("download", ignoreCase = true)) {
                                    null
                                } else {
                                    uri
                                }
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                }
            )
            
            // Try each method until one succeeds
            for (method in methods) {
                val uri = method()
                if (uri != null) {
                    try {
                        // Try to launch with this URI
                        launchSystemFileManager(uri)
                        return // Success, exit early
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // Continue to next method
                    }
                }
            }
            
            // All methods failed - show directory path
            val message = getString(R.string.cannot_open_folder) + "\n\n" + 
                    getString(R.string.save_location_label) + " " + path
            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
            ConfirmationDialog(
                this,
                message = message,
                positive = R.string.ok,
                negative = 0,
                cancelOnTouchOutside = true,
                blurTarget = blurTarget
            ) {}
        } catch (e: Exception) {
            e.printStackTrace()
            toast(R.string.cannot_open_folder)
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

