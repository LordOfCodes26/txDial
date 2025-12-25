package com.android.dialer.recording

import android.content.Context
import android.os.Environment
import android.telecom.Call
import android.util.Log
import com.android.dialer.helpers.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages file naming and storage locations for call recordings
 * Supports customizable file name templates and save locations like BCR
 */
class RecordingFileManager(private val context: Context) {
    
    companion object {
        private const val TAG = "RecordingFileManager"
        
        // Default save locations
        const val LOCATION_APP_FILES = 0        // App's external files directory
        const val LOCATION_MUSIC = 1            // Music directory
        const val LOCATION_DOCUMENTS = 2        // Documents directory
        const val LOCATION_RECORDINGS = 3       // Recordings directory (Android 12+)
        const val LOCATION_CUSTOM = 4           // Custom directory
        
        // File name template variables
        const val VAR_DATE = "{date}"           // YYYYMMDD
        const val VAR_TIME = "{time}"           // HHmmss
        const val VAR_TIMESTAMP = "{timestamp}" // YYYYMMDD_HHmmss.SSS
        const val VAR_DIRECTION = "{direction}" // in/out
        const val VAR_PHONE_NUMBER = "{phone_number}" // Phone number
        const val VAR_CALLER_NAME = "{caller_name}"   // Contact name
        const val VAR_SIM_SLOT = "{sim_slot}"   // SIM slot (1, 2)
        
        // Default template (BCR-style)
        const val DEFAULT_TEMPLATE = "{timestamp}_{direction}_{phone_number}"
        
        // File extensions
        private val FORMAT_EXTENSIONS = mapOf(
            CallRecorder.OutputFormat.WAV to "wav",
            CallRecorder.OutputFormat.OGG_OPUS to "oga",
            CallRecorder.OutputFormat.M4A_AAC to "m4a",
            CallRecorder.OutputFormat.FLAC to "flac"
        )
    }
    
    private val config = Config(context)
    
    /**
     * Get the save directory based on user settings
     */
    fun getSaveDirectory(): File {
        val location = config.recordingSaveLocation
        
        val directory = when (location) {
            LOCATION_APP_FILES -> {
                // Default: App's external files directory
                File(context.getExternalFilesDir(null), "Recordings")
            }
            
            LOCATION_MUSIC -> {
                // Music/Call Recordings
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "Call Recordings"
                )
            }
            
            LOCATION_DOCUMENTS -> {
                // Documents/Call Recordings
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "Call Recordings"
                )
            }
            
            LOCATION_RECORDINGS -> {
                // Recordings/Call Recordings (Android 12+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    File(
                        Environment.getExternalStoragePublicDirectory("Recordings"),
                        "Call Recordings"
                    )
                } else {
                    // Fallback to Music for older versions
                    File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        "Call Recordings"
                    )
                }
            }
            
            LOCATION_CUSTOM -> {
                // Custom directory specified by user
                val customPath = config.recordingCustomPath
                if (customPath.isNotEmpty()) {
                    File(customPath)
                } else {
                    // Fallback to default if custom path is empty
                    File(context.getExternalFilesDir(null), "Recordings")
                }
            }
            
            else -> {
                // Default fallback
                File(context.getExternalFilesDir(null), "Recordings")
            }
        }
        
        // Create directory if it doesn't exist
        if (!directory.exists()) {
            val created = directory.mkdirs()
            if (!created) {
                Log.e(TAG, "Failed to create directory: ${directory.absolutePath}")
            }
        }
        
        return directory
    }
    
    /**
     * Generate file name based on template and call details
     */
    fun generateFileName(
        call: Call,
        format: CallRecorder.OutputFormat,
        callerName: String? = null
    ): String {
        val template = config.recordingFileNameTemplate.ifEmpty { DEFAULT_TEMPLATE }
        val extension = FORMAT_EXTENSIONS[format] ?: "wav"
        
        var fileName = template
        
        // Replace date/time variables
        val now = Date()
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        val timeFormat = SimpleDateFormat("HHmmss", Locale.US)
        val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss.SSS", Locale.US)
        val timezoneFormat = SimpleDateFormat("Z", Locale.US)
        
        fileName = fileName.replace(VAR_DATE, dateFormat.format(now))
        fileName = fileName.replace(VAR_TIME, timeFormat.format(now))
        fileName = fileName.replace(VAR_TIMESTAMP, "${timestampFormat.format(now)}${timezoneFormat.format(now)}")
        
        // Replace call direction
        val direction = if (call.details.callDirection == Call.Details.DIRECTION_INCOMING) {
            "in"
        } else {
            "out"
        }
        fileName = fileName.replace(VAR_DIRECTION, direction)
        
        // Replace phone number
        val phoneNumber = call.details.handle?.schemeSpecificPart ?: "unknown"
        val sanitizedNumber = sanitizeForFileName(phoneNumber)
        fileName = fileName.replace(VAR_PHONE_NUMBER, sanitizedNumber)
        
        // Replace caller name if available
        if (callerName != null) {
            val sanitizedName = sanitizeForFileName(callerName)
            fileName = fileName.replace(VAR_CALLER_NAME, sanitizedName)
        } else {
            // Remove caller name variable if no name is available
            fileName = fileName.replace(VAR_CALLER_NAME, "")
        }
        
        // Replace SIM slot
        val simSlot = getSimSlot(call)
        fileName = fileName.replace(VAR_SIM_SLOT, simSlot)
        
        // Clean up any double underscores or leading/trailing underscores
        fileName = fileName.replace(Regex("_+"), "_")
            .trim('_')
        
        // Ensure filename is not empty
        if (fileName.isEmpty()) {
            fileName = "recording_${System.currentTimeMillis()}"
        }
        
        return "$fileName.$extension"
    }
    
    /**
     * Create full output file with directory and generated name
     */
    fun createOutputFile(
        call: Call,
        format: CallRecorder.OutputFormat,
        callerName: String? = null
    ): File {
        val directory = getSaveDirectory()
        val fileName = generateFileName(call, format, callerName)
        
        var outputFile = File(directory, fileName)
        
        // Handle file name conflicts
        if (outputFile.exists()) {
            var counter = 1
            val nameWithoutExtension = fileName.substringBeforeLast('.')
            val extension = fileName.substringAfterLast('.')
            
            while (outputFile.exists() && counter < 1000) {
                outputFile = File(directory, "${nameWithoutExtension}_$counter.$extension")
                counter++
            }
        }
        
        Log.i(TAG, "Output file: ${outputFile.absolutePath}")
        return outputFile
    }
    
    /**
     * Sanitize string for use in file names
     */
    private fun sanitizeForFileName(input: String): String {
        // Remove or replace characters that are invalid in file names
        return input
            .replace(Regex("[\\\\/:*?\"<>|]"), "") // Remove invalid chars
            .replace(Regex("\\s+"), "_")           // Replace spaces with underscore
            .replace(Regex("[^a-zA-Z0-9_+\\-.]"), "") // Keep only safe chars
            .take(50) // Limit length to avoid too long file names
    }
    
    /**
     * Get SIM slot number for the call
     */
    private fun getSimSlot(call: Call): String {
        return try {
            val phoneAccountHandle = call.details.accountHandle
            // Try to determine SIM slot from phone account handle
            // This is device-specific and may not always work
            phoneAccountHandle?.id?.lastOrNull()?.toString() ?: "1"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SIM slot", e)
            "1"
        }
    }
    
    /**
     * Get available save locations
     */
    fun getAvailableLocations(): List<SaveLocation> {
        return listOf(
            SaveLocation(
                id = LOCATION_APP_FILES,
                name = "App Storage",
                path = File(context.getExternalFilesDir(null), "Recordings").absolutePath,
                description = "Deleted when app is uninstalled"
            ),
            SaveLocation(
                id = LOCATION_MUSIC,
                name = "Music",
                path = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "Call Recordings"
                ).absolutePath,
                description = "Music/Call Recordings"
            ),
            SaveLocation(
                id = LOCATION_DOCUMENTS,
                name = "Documents",
                path = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "Call Recordings"
                ).absolutePath,
                description = "Documents/Call Recordings"
            ),
            SaveLocation(
                id = LOCATION_RECORDINGS,
                name = "Recordings",
                path = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    File(
                        Environment.getExternalStoragePublicDirectory("Recordings"),
                        "Call Recordings"
                    ).absolutePath
                } else {
                    "Requires Android 12+"
                },
                description = "Recordings/Call Recordings (Android 12+)"
            ),
            SaveLocation(
                id = LOCATION_CUSTOM,
                name = "Custom",
                path = config.recordingCustomPath.ifEmpty { "Not set" },
                description = "User-defined location"
            )
        )
    }
    
    /**
     * Get example file name based on current template
     */
    fun getExampleFileName(format: CallRecorder.OutputFormat = CallRecorder.OutputFormat.WAV): String {
        val template = config.recordingFileNameTemplate.ifEmpty { DEFAULT_TEMPLATE }
        val extension = FORMAT_EXTENSIONS[format] ?: "wav"
        
        var example = template
        
        // Replace with example values
        example = example.replace(VAR_DATE, "20231221")
        example = example.replace(VAR_TIME, "143025")
        example = example.replace(VAR_TIMESTAMP, "20231221_143025.123+0000")
        example = example.replace(VAR_DIRECTION, "out")
        example = example.replace(VAR_PHONE_NUMBER, "1234567890")
        example = example.replace(VAR_CALLER_NAME, "John_Doe")
        example = example.replace(VAR_SIM_SLOT, "1")
        
        // Clean up
        example = example.replace(Regex("_+"), "_").trim('_')
        
        if (example.isEmpty()) {
            example = "recording"
        }
        
        return "$example.$extension"
    }
    
    /**
     * Validate file name template
     */
    fun isValidTemplate(template: String): Boolean {
        if (template.isEmpty()) return false
        
        // Check for invalid characters
        val invalidChars = listOf("\\", "/", ":", "*", "?", "\"", "<", ">", "|")
        for (char in invalidChars) {
            if (template.contains(char)) return false
        }
        
        // Template must contain at least one variable or be static text
        return true
    }
    
    /**
     * Get available template variables
     */
    fun getTemplateVariables(): List<TemplateVariable> {
        return listOf(
            TemplateVariable(VAR_TIMESTAMP, "Timestamp", "20231221_143025.123+0000"),
            TemplateVariable(VAR_DATE, "Date", "20231221"),
            TemplateVariable(VAR_TIME, "Time", "143025"),
            TemplateVariable(VAR_DIRECTION, "Direction", "in or out"),
            TemplateVariable(VAR_PHONE_NUMBER, "Phone Number", "1234567890"),
            TemplateVariable(VAR_CALLER_NAME, "Caller Name", "John_Doe"),
            TemplateVariable(VAR_SIM_SLOT, "SIM Slot", "1 or 2")
        )
    }
}

/**
 * Data class for save location information
 */
data class SaveLocation(
    val id: Int,
    val name: String,
    val path: String,
    val description: String
)

/**
 * Data class for template variable information
 */
data class TemplateVariable(
    val variable: String,
    val name: String,
    val example: String
)

