package com.android.dialer.recording

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.provider.ContactsContract
import android.telecom.Call
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Call recorder implementation based on BCR (Basic Call Recorder)
 * Handles audio capture and encoding for phone calls
 */
class CallRecorder(
    private val context: Context,
    private val call: Call,
    private val outputFormat: OutputFormat = OutputFormat.OGG_OPUS,
    private val notificationManager: RecordingNotificationManager? = null
) {
    companion object {
        private const val TAG = "CallRecorder"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isPaused = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var outputFile: File? = null
    private var encoder: AudioEncoder? = null
    private val fileManager = RecordingFileManager(context)
    
    // Recording statistics
    private var framesTotal = 0L
    private var framesEncoded = 0L
    private var bufferOverruns = 0
    private var wasEverPaused = false
    private var wasEverHolding = false
    
    enum class OutputFormat {
        OGG_OPUS,
        M4A_AAC,
        FLAC,
        WAV
    }

    /**
     * Start recording the call
     */
    fun startRecording(): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }

        try {
            // Calculate buffer size
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            
            if (minBufferSize <= 0) {
                Log.e(TAG, "Invalid buffer size: $minBufferSize")
                return false
            }

            val bufferSize = minBufferSize * BUFFER_SIZE_MULTIPLIER

            // Create AudioRecord with VOICE_CALL audio source
            // Note: VOICE_CALL requires system permissions
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized properly")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            // Create output file using file manager
            outputFile = fileManager.createOutputFile(call, outputFormat)
            
            // Create encoder
            encoder = createEncoder(outputFile!!)

            // Start recording
            audioRecord?.startRecording()
            isRecording = true

            // Start recording loop in coroutine
            recordingJob = scope.launch {
                recordingLoop(bufferSize)
            }

            // Show notification with contact name if available
            val phoneNumber = call.details.handle?.schemeSpecificPart
            val contactName = phoneNumber?.let { getContactName(it) }
            notificationManager?.showRecordingNotification(phoneNumber, contactName, isPaused = false)

            Log.i(TAG, "Recording started for call")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            return false
        }
    }

    /**
     * Stop recording the call
     */
    fun stopRecording() {
        if (!isRecording) {
            return
        }

        isRecording = false
        recordingJob?.cancel()
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }

        cleanup()
        
        // Save metadata
        saveMetadata()
        
        // Hide notification
        notificationManager?.cancelNotification()
        
        Log.i(TAG, "Recording stopped. File: ${outputFile?.absolutePath}")
    }

    /**
     * Pause recording (e.g., when call is on hold)
     */
    fun pauseRecording() {
        if (isRecording && !isPaused) {
            isPaused = true
            wasEverPaused = true
            
            // Update notification to show paused state
            val phoneNumber = call.details.handle?.schemeSpecificPart
            val contactName = phoneNumber?.let { getContactName(it) }
            notificationManager?.updateNotification(phoneNumber, contactName, isPaused = true)
            
            Log.d(TAG, "Recording paused")
        }
    }

    /**
     * Resume recording
     */
    fun resumeRecording() {
        if (isRecording && isPaused) {
            isPaused = false
            
            // Update notification to show recording state
            val phoneNumber = call.details.handle?.schemeSpecificPart
            val contactName = phoneNumber?.let { getContactName(it) }
            notificationManager?.updateNotification(phoneNumber, contactName, isPaused = false)
            
            Log.d(TAG, "Recording resumed")
        }
    }

    /**
     * Mark that call was on hold
     */
    fun markOnHold() {
        wasEverHolding = true
    }

    /**
     * Main recording loop - reads audio and encodes it
     */
    private suspend fun recordingLoop(bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2) // 16-bit samples

        while (isRecording && audioRecord != null) {
            try {
                val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                if (samplesRead > 0) {
                    framesTotal += samplesRead

                    // Only encode if not paused
                    if (!isPaused) {
                        encoder?.encode(buffer, samplesRead)
                        framesEncoded += samplesRead
                    }
                } else if (samplesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "Invalid operation error")
                    break
                } else if (samplesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Bad value error")
                    break
                } else if (samplesRead == AudioRecord.ERROR) {
                    bufferOverruns++
                    Log.w(TAG, "Buffer overrun #$bufferOverruns")
                }

                // Check for overruns
                val overrunCount = audioRecord?.recordingState
                if (overrunCount == AudioRecord.RECORDSTATE_STOPPED) {
                    Log.w(TAG, "Recording stopped unexpectedly")
                    break
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in recording loop", e)
                break
            }
        }

        // Finalize encoding
        encoder?.finalize()
    }


    /**
     * Create appropriate encoder based on format
     */
    private fun createEncoder(outputFile: File): AudioEncoder {
        return when (outputFormat) {
            OutputFormat.OGG_OPUS -> OpusEncoder(outputFile, SAMPLE_RATE)
            OutputFormat.M4A_AAC -> AacEncoder(outputFile, SAMPLE_RATE)
            OutputFormat.FLAC -> FlacEncoder(outputFile, SAMPLE_RATE)
            OutputFormat.WAV -> WavEncoder(outputFile, SAMPLE_RATE)
        }
    }

    /**
     * Save recording metadata as JSON
     */
    private fun saveMetadata() {
        try {
            val metadataFile = File(
                outputFile?.parentFile,
                "${outputFile?.nameWithoutExtension}.json"
            )

            val metadata = buildString {
                appendLine("{")
                appendLine("  \"timestamp\": \"${Date()}\",")
                appendLine("  \"direction\": \"${if (call.details.callDirection == Call.Details.DIRECTION_INCOMING) "incoming" else "outgoing"}\",")
                appendLine("  \"phone_number\": \"${call.details.handle?.schemeSpecificPart ?: "unknown"}\",")
                appendLine("  \"format\": \"$outputFormat\",")
                appendLine("  \"sample_rate\": $SAMPLE_RATE,")
                appendLine("  \"frames_total\": $framesTotal,")
                appendLine("  \"frames_encoded\": $framesEncoded,")
                appendLine("  \"buffer_overruns\": $bufferOverruns,")
                appendLine("  \"was_ever_paused\": $wasEverPaused,")
                appendLine("  \"was_ever_holding\": $wasEverHolding,")
                appendLine("  \"duration_secs_total\": ${framesTotal.toFloat() / SAMPLE_RATE},")
                appendLine("  \"duration_secs_encoded\": ${framesEncoded.toFloat() / SAMPLE_RATE}")
                appendLine("}")
            }

            FileOutputStream(metadataFile).use { output ->
                output.write(metadata.toByteArray())
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata", e)
        }
    }

    /**
     * Get contact name from phone number (BCR-style)
     */
    private fun getContactName(phoneNumber: String): String? {
        return try {
            val normalizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon()
                .appendPath(normalizedNumber)
                .build()
            
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        cursor.getString(nameIndex)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not get contact name for $phoneNumber", e)
            null
        }
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        try {
            encoder?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing encoder", e)
        }
        encoder = null
    }
}

/**
 * Base interface for audio encoders
 */
interface AudioEncoder {
    fun encode(buffer: ShortArray, samplesRead: Int)
    fun finalize()
    fun close()
}

/**
 * Simple WAV encoder implementation
 */
class WavEncoder(
    private val outputFile: File,
    private val sampleRate: Int
) : AudioEncoder {
    private var output: FileOutputStream? = null
    private var dataSize = 0

    init {
        output = FileOutputStream(outputFile)
        writeWavHeader(output!!, sampleRate, 0) // Write header with 0 size initially
    }

    override fun encode(buffer: ShortArray, samplesRead: Int) {
        val bytes = ByteArray(samplesRead * 2)
        for (i in 0 until samplesRead) {
            val sample = buffer[i].toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        output?.write(bytes)
        dataSize += bytes.size
    }

    override fun finalize() {
        // Update WAV header with correct size
        output?.close()
        
        val randomAccessFile = java.io.RandomAccessFile(outputFile, "rw")
        randomAccessFile.seek(0)
        val header = ByteArray(44)
        writeWavHeaderToArray(header, sampleRate, dataSize)
        randomAccessFile.write(header)
        randomAccessFile.close()
    }

    override fun close() {
        output?.close()
    }

    private fun writeWavHeader(output: FileOutputStream, sampleRate: Int, dataSize: Int) {
        val header = ByteArray(44)
        writeWavHeaderToArray(header, sampleRate, dataSize)
        output.write(header)
    }

    private fun writeWavHeaderToArray(header: ByteArray, sampleRate: Int, dataSize: Int) {
        val totalSize = dataSize + 36
        val byteRate = sampleRate * 2 // 16-bit mono

        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        
        // File size
        header[4] = (totalSize and 0xFF).toByte()
        header[5] = ((totalSize shr 8) and 0xFF).toByte()
        header[6] = ((totalSize shr 16) and 0xFF).toByte()
        header[7] = ((totalSize shr 24) and 0xFF).toByte()

        // WAVE header
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        
        header[16] = 16 // Subchunk size
        header[17] = 0
        header[18] = 0
        header[19] = 0
        
        header[20] = 1 // PCM format
        header[21] = 0
        
        header[22] = 1 // Mono
        header[23] = 0
        
        // Sample rate
        header[24] = (sampleRate and 0xFF).toByte()
        header[25] = ((sampleRate shr 8) and 0xFF).toByte()
        header[26] = ((sampleRate shr 16) and 0xFF).toByte()
        header[27] = ((sampleRate shr 24) and 0xFF).toByte()

        // Byte rate
        header[28] = (byteRate and 0xFF).toByte()
        header[29] = ((byteRate shr 8) and 0xFF).toByte()
        header[30] = ((byteRate shr 16) and 0xFF).toByte()
        header[31] = ((byteRate shr 24) and 0xFF).toByte()

        header[32] = 2 // Block align
        header[33] = 0
        
        header[34] = 16 // Bits per sample
        header[35] = 0

        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        
        header[40] = (dataSize and 0xFF).toByte()
        header[41] = ((dataSize shr 8) and 0xFF).toByte()
        header[42] = ((dataSize shr 16) and 0xFF).toByte()
        header[43] = ((dataSize shr 24) and 0xFF).toByte()
    }
}

/**
 * Placeholder for Opus encoder
 * Full implementation would use MediaCodec or native library
 */
class OpusEncoder(outputFile: File, sampleRate: Int) : AudioEncoder {
    // TODO: Implement using MediaCodec or native Opus library
    private val wavEncoder = WavEncoder(outputFile, sampleRate)
    
    override fun encode(buffer: ShortArray, samplesRead: Int) {
        wavEncoder.encode(buffer, samplesRead)
    }

    override fun finalize() {
        wavEncoder.finalize()
    }

    override fun close() {
        wavEncoder.close()
    }
}

/**
 * Placeholder for AAC encoder
 */
class AacEncoder(outputFile: File, sampleRate: Int) : AudioEncoder {
    private val wavEncoder = WavEncoder(outputFile, sampleRate)
    
    override fun encode(buffer: ShortArray, samplesRead: Int) {
        wavEncoder.encode(buffer, samplesRead)
    }

    override fun finalize() {
        wavEncoder.finalize()
    }

    override fun close() {
        wavEncoder.close()
    }
}

/**
 * Placeholder for FLAC encoder
 */
class FlacEncoder(outputFile: File, sampleRate: Int) : AudioEncoder {
    private val wavEncoder = WavEncoder(outputFile, sampleRate)
    
    override fun encode(buffer: ShortArray, samplesRead: Int) {
        wavEncoder.encode(buffer, samplesRead)
    }

    override fun finalize() {
        wavEncoder.finalize()
    }

    override fun close() {
        wavEncoder.close()
    }
}

