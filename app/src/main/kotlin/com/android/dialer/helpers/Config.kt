package com.android.dialer.helpers

import android.content.Context
import android.net.Uri
import android.graphics.Typeface
import android.media.RingtoneManager
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.goodwy.commons.extensions.getDefaultAlarmSound
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.goodwy.commons.helpers.BaseConfig
import com.android.dialer.extensions.getPhoneAccountHandleModel
import com.android.dialer.extensions.putPhoneAccountHandle
import android.provider.CallLog.Calls
import com.android.dialer.models.CallerNote
import com.android.dialer.models.RecentCall
import com.android.dialer.models.SpeedDial
import androidx.core.content.edit
import com.goodwy.commons.helpers.ON_CONTACT_CLICK
import com.android.dialer.R
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
        private val gson = Gson()
    }

    private val regionHint: String by lazy {
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso,
            Locale.getDefault().country
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.US)
            .orEmpty()
    }

    fun getSpeedDialValues(): ArrayList<SpeedDial> {
        val speedDialType = object : TypeToken<List<SpeedDial>>() {}.type
        val speedDialValues = gson.fromJson<ArrayList<SpeedDial>>(speedDial, speedDialType) ?: mutableListOf<SpeedDial>()

        for (i in 1..9) {
            val speedDial = SpeedDial(i, "", "")
            if (speedDialValues.firstOrNull { it.id == i } == null) {
                speedDialValues.add(speedDial)
            }
        }

        return ArrayList(speedDialValues)
    }

    fun saveCustomSIM(number: String, handle: PhoneAccountHandle) {
        prefs.edit {
            putPhoneAccountHandle(
                key = getKeyForCustomSIM(number),
                parcelable = handle
            )
        }
    }

    fun getCustomSIM(number: String): PhoneAccountHandle? {
        val key = getKeyForCustomSIM(number)
        prefs.getPhoneAccountHandleModel(key, null)?.let {
            return it.toPhoneAccountHandle()
        }

        // fallback for old unstable keys. should be removed in future versions
        val migratedHandle = prefs.all.keys
            .filterIsInstance<String>()
            .filter { it.startsWith(REMEMBER_SIM_PREFIX) }
            .firstOrNull {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.compare(
                    it.removePrefix(REMEMBER_SIM_PREFIX),
                    normalizeCustomSIMNumber(number)
                )
            }?.let { legacyKey ->
                prefs.getPhoneAccountHandleModel(legacyKey, null)?.let {
                    val handle = it.toPhoneAccountHandle()
                    prefs.edit {
                        remove(legacyKey)
                        putPhoneAccountHandle(key, handle)
                    }
                    handle
                }
            }

        return migratedHandle
    }

    fun removeCustomSIM(number: String) {
        prefs.edit { remove(getKeyForCustomSIM(number)) }
    }

    private fun getKeyForCustomSIM(number: String): String {
        return REMEMBER_SIM_PREFIX + normalizeCustomSIMNumber(number)
    }

    private fun normalizeCustomSIMNumber(number: String): String {
        val decoded = Uri.decode(number).removePrefix("tel:")
        val formatted = PhoneNumberUtils.formatNumberToE164(decoded, regionHint)
        return formatted ?: PhoneNumberUtils.normalizeNumber(decoded)
    }

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit { putInt(SHOW_TABS, showTabs) }

    var groupSubsequentCalls: Boolean
        get() = prefs.getBoolean(GROUP_SUBSEQUENT_CALLS, true)
        set(groupSubsequentCalls) = prefs.edit { putBoolean(GROUP_SUBSEQUENT_CALLS, groupSubsequentCalls) }

    var openDialPadAtLaunch: Boolean
        get() = prefs.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH, false)
        set(openDialPad) = prefs.edit { putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, openDialPad) }

    var disableProximitySensor: Boolean
        get() = prefs.getBoolean(DISABLE_PROXIMITY_SENSOR, false)
        set(disableProximitySensor) = prefs.edit { putBoolean(DISABLE_PROXIMITY_SENSOR, disableProximitySensor) }

    var disableSwipeToAnswer: Boolean
        get() = prefs.getBoolean(DISABLE_SWIPE_TO_ANSWER, true)
        set(disableSwipeToAnswer) = prefs.edit { putBoolean(DISABLE_SWIPE_TO_ANSWER, disableSwipeToAnswer) }

    var wasOverlaySnackbarConfirmed: Boolean
        get() = prefs.getBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, false)
        set(wasOverlaySnackbarConfirmed) = prefs.edit { putBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, wasOverlaySnackbarConfirmed) }

    var dialpadVibration: Boolean
        get() = prefs.getBoolean(DIALPAD_VIBRATION, true)
        set(dialpadVibration) = prefs.edit { putBoolean(DIALPAD_VIBRATION, dialpadVibration) }

    var hideDialpadNumbers: Boolean
        get() = prefs.getBoolean(HIDE_DIALPAD_NUMBERS, false)
        set(hideDialpadNumbers) = prefs.edit { putBoolean(HIDE_DIALPAD_NUMBERS, hideDialpadNumbers) }

    var dialpadSecondaryLanguage: String?
        get() = prefs.getString(DIALPAD_SECONDARY_LANGUAGE, LANGUAGE_SYSTEM)
        set(dialpadSecondaryLanguage) = prefs.edit { putString(DIALPAD_SECONDARY_LANGUAGE, dialpadSecondaryLanguage) }

    var dialpadBeeps: Boolean
        get() = prefs.getBoolean(DIALPAD_BEEPS, false)
        set(dialpadBeeps) = prefs.edit { putBoolean(DIALPAD_BEEPS, dialpadBeeps) }

    var alwaysShowFullscreen: Boolean // not used showIncomingCallsFullScreen
        get() = prefs.getBoolean(ALWAYS_SHOW_FULLSCREEN, false)
        set(alwaysShowFullscreen) = prefs.edit { putBoolean(ALWAYS_SHOW_FULLSCREEN, alwaysShowFullscreen) }

    //Goodwy
    var showIncomingCallsFullScreen: Boolean
        get() = prefs.getBoolean(SHOW_INCOMING_CALLS_FULL_SCREEN, false)
        set(showIncomingCallsFullScreen) = prefs.edit { putBoolean(SHOW_INCOMING_CALLS_FULL_SCREEN, showIncomingCallsFullScreen) }

    var transparentCallScreen: Boolean  //not used
        get() = prefs.getBoolean(TRANSPARENT_CALL_SCREEN, false)
        set(transparentCallScreen) = prefs.edit { putBoolean(TRANSPARENT_CALL_SCREEN, transparentCallScreen) }

    var missedCallNotifications: Boolean
        get() = prefs.getBoolean(MISSED_CALL_NOTIFICATIONS, false)
        set(missedCallNotifications) = prefs.edit { putBoolean(MISSED_CALL_NOTIFICATIONS, missedCallNotifications) }

    var hideDialpadLetters: Boolean
        get() = prefs.getBoolean(HIDE_DIALPAD_LETTERS, false)
        set(hideDialpadLetters) = prefs.edit { putBoolean(HIDE_DIALPAD_LETTERS, hideDialpadLetters) }

    var backgroundCallScreen: Int
        get() = prefs.getInt(BACKGROUND_CALL_SCREEN, BLUR_AVATAR)
        set(backgroundCallScreen) = prefs.edit { putInt(BACKGROUND_CALL_SCREEN, backgroundCallScreen) }

    var backgroundCallCustomImage: String
        get() = prefs.getString(BACKGROUND_CALL_CUSTOM_IMAGE, "") ?: ""
        set(backgroundCallCustomImage) = prefs.edit { putString(BACKGROUND_CALL_CUSTOM_IMAGE, backgroundCallCustomImage) }

    var backgroundCallCustomAlpha: Int
        get() = prefs.getInt(BACKGROUND_CALL_CUSTOM_ALPHA, 80)
        set(backgroundCallCustomAlpha) = prefs.edit { putInt(BACKGROUND_CALL_CUSTOM_ALPHA, backgroundCallCustomAlpha) }

    var backgroundCallCustomBlurRadius: Float
        get() = prefs.getFloat(BACKGROUND_CALL_CUSTOM_BLUR_RADIUS, 20f)
        set(backgroundCallCustomBlurRadius) = prefs.edit { putFloat(BACKGROUND_CALL_CUSTOM_BLUR_RADIUS, backgroundCallCustomBlurRadius) }

    var backgroundCallCustomVideo: String
        get() = prefs.getString(BACKGROUND_CALL_CUSTOM_VIDEO, "") ?: ""
        set(backgroundCallCustomVideo) = prefs.edit { putString(BACKGROUND_CALL_CUSTOM_VIDEO, backgroundCallCustomVideo) }

    var dialpadStyle: Int
        get() = prefs.getInt(DIALPAD_STYLE, DIALPAD_IOS)
        set(dialpadStyle) = prefs.edit { putInt(DIALPAD_STYLE, dialpadStyle) }

    var dialpadSize: Int
        get() = prefs.getInt(DIALPAD_SIZE, 85)
        set(dialpadSize) = prefs.edit { putInt(DIALPAD_SIZE, dialpadSize) }

    var dialpadBottomMargin: Int
        get() = prefs.getInt(DIALPAD_BOTTOM_MARGIN, 150)
        set(dialpadBottomMargin) = prefs.edit { putInt(DIALPAD_BOTTOM_MARGIN, dialpadBottomMargin) }

    var callButtonPrimarySize: Int
        get() = prefs.getInt(CALL_BUTTON_PRIMARY_SIZE, 100)
        set(callButtonPrimarySize) = prefs.edit { putInt(CALL_BUTTON_PRIMARY_SIZE, callButtonPrimarySize) }

    var callButtonSecondarySize: Int
        get() = prefs.getInt(CALL_BUTTON_SECONDARY_SIZE, 100)
        set(callButtonSecondarySize) = prefs.edit { putInt(CALL_BUTTON_SECONDARY_SIZE, callButtonSecondarySize) }

    var answerStyle: Int
        get() = prefs.getInt(ANSWER_STYLE, ANSWER_BUTTON)
        set(answerStyle) = prefs.edit { putInt(ANSWER_STYLE, answerStyle) }

    var showCallerDescription: Int
        get() = prefs.getInt(SHOW_CALLER_DESCRIPTION, SHOW_CALLER_COMPANY)
        set(showCallerDescription) = prefs.edit { putInt(SHOW_CALLER_DESCRIPTION, showCallerDescription) }

    var showWarningAnonymousCall: Boolean
        get() = prefs.getBoolean(SHOW_WARNING_ANONYMOUS_CALL, true)
        set(showWarningAnonymousCall) = prefs.edit { putBoolean(SHOW_WARNING_ANONYMOUS_CALL, showWarningAnonymousCall) }

    var callVibration: Boolean
        get() = prefs.getBoolean(CALL_VIBRATION, true)
        set(callVibration) = prefs.edit { putBoolean(CALL_VIBRATION, callVibration) }

    var callStartEndVibration: Boolean
        get() = prefs.getBoolean(CALL_START_END_VIBRATION, true)
        set(callStartEndVibration) = prefs.edit { putBoolean(CALL_START_END_VIBRATION, callStartEndVibration) }

    var toneVolume: Int
        get() = prefs.getInt(TONE_VOLUME, 80)
        set(toneVolume) = prefs.edit { putInt(TONE_VOLUME, toneVolume) }

    var groupAllCalls: Boolean
        get() = prefs.getBoolean(GROUP_ALL_CALLS, false)
        set(groupAllCalls) = prefs.edit { putBoolean(GROUP_ALL_CALLS, groupAllCalls) }

    var showRecentCallsOnDialpad: Boolean
        get() = prefs.getBoolean(SHOW_RECENT_CALLS_ON_DIALPAD, true)
        set(showRecentCallsOnDialpad) = prefs.edit { putBoolean(SHOW_RECENT_CALLS_ON_DIALPAD, showRecentCallsOnDialpad) }

    var blockCallFromAnotherApp: Boolean
        get() = prefs.getBoolean(BLOCK_CALL_FROM_ANOTHER_APP, false)
        set(blockCallFromAnotherApp) = prefs.edit { putBoolean(BLOCK_CALL_FROM_ANOTHER_APP, blockCallFromAnotherApp) }

    var needUpdateRecents: Boolean
        get() = prefs.getBoolean(NEED_UPDATE_RECENTS, false)
        set(needUpdateRecents) = prefs.edit { putBoolean(NEED_UPDATE_RECENTS, needUpdateRecents) }

    var recentCallsCache: String
        get() = prefs.getString(RECENT_CALL, "")!!
        set(recentCallsCache) = prefs.edit { putString(RECENT_CALL, recentCallsCache) }

    fun parseRecentCallsCache(): ArrayList<RecentCall> {
        val listType = object : TypeToken<List<RecentCall>>() {}.type
        val result = gson.fromJson<ArrayList<RecentCall>>(recentCallsCache, listType) ?: mutableListOf<RecentCall>()
        return ArrayList(result)
    }

    var queryLimitRecent: Int
        get() = prefs.getInt(QUERY_LIMIT_RECENT, QUERY_LIMIT_MEDIUM_VALUE)
        set(queryLimitRecent) = prefs.edit { putInt(QUERY_LIMIT_RECENT, queryLimitRecent) }

    var callButtonStyle: Int
        get() = prefs.getInt(CALL_BUTTON_STYLE, IOS16)
        set(callButtonStyle) = prefs.edit { putInt(CALL_BUTTON_STYLE, callButtonStyle) }

    var quickAnswers: ArrayList<String>
        get(): ArrayList<String> {
            val defaultList = arrayListOf(
                ContextCompat.getString(context, com.android.dialer.R.string.message_call_later),
                ContextCompat.getString(context, com.android.dialer.R.string.message_on_my_way),
                ContextCompat.getString(context, com.android.dialer.R.string.message_cant_talk_right_now)
            )
            return ArrayList(prefs.getString(QUICK_ANSWERS, null)?.lines()?.map { it } ?: defaultList)
        }
        set(quickAnswers) = prefs.edit { putString(QUICK_ANSWERS, quickAnswers.joinToString(separator = "\n")) }

    var callUsingSameSim: Boolean
        get() = prefs.getBoolean(CALL_USING_SAME_SIM, false)
        set(callUsingSameSim) = prefs.edit { putBoolean(CALL_USING_SAME_SIM, callUsingSameSim) }

    var showVoicemailIcon: Boolean
        get() = prefs.getBoolean(SHOW_VOICEMAIL_ICON, false)
        set(showVoicemailIcon) = prefs.edit { putBoolean(SHOW_VOICEMAIL_ICON, showVoicemailIcon) }

    var simDialogStyle: Int
        get() = prefs.getInt(SIM_DIALOG_STYLE, SIM_DIALOG_STYLE_LIST)
        set(simDialogStyle) = prefs.edit { putInt(SIM_DIALOG_STYLE, simDialogStyle) }

    var autoSimSelectEnabled: Boolean
        get() = prefs.getBoolean(AUTO_SIM_SELECT_ENABLED, false)
        set(autoSimSelectEnabled) = prefs.edit { putBoolean(AUTO_SIM_SELECT_ENABLED, autoSimSelectEnabled) }

    var autoSimSelectDelaySeconds: Int
        get() = prefs.getInt(AUTO_SIM_SELECT_DELAY_SECONDS, DEFAULT_AUTO_SIM_SELECT_DELAY)
        set(autoSimSelectDelaySeconds) = prefs.edit { putInt(AUTO_SIM_SELECT_DELAY_SECONDS, autoSimSelectDelaySeconds) }

    var autoSimSelectIndex: Int
        get() = prefs.getInt(AUTO_SIM_SELECT_INDEX, 0)  // 0 = SIM 1, 1 = SIM 2
        set(autoSimSelectIndex) = prefs.edit { putInt(AUTO_SIM_SELECT_INDEX, autoSimSelectIndex) }

    var dialpadSecondaryTypeface: Int
        get() = prefs.getInt(DIALPAD_SECONDARY_TYPEFACE, Typeface.NORMAL)
        set(dialpadSecondaryTypeface) = prefs.edit { putInt(DIALPAD_SECONDARY_TYPEFACE, dialpadSecondaryTypeface) }

    var dialpadHashtagLongClick: Int
        get() = prefs.getInt(DIALPAD_HASHTAG_LONG_CLICK, DIALPAD_LONG_CLICK_WAIT)
        set(dialpadHashtagLongClick) = prefs.edit { putInt(DIALPAD_HASHTAG_LONG_CLICK, dialpadHashtagLongClick) }

    var dialpadClearWhenStartCall: Boolean
        get() = prefs.getBoolean(DIALPAD_CLEAR_WHEN_START_CALL, false)
        set(dialpadClearWhenStartCall) = prefs.edit {
            putBoolean(DIALPAD_CLEAR_WHEN_START_CALL, dialpadClearWhenStartCall)
        }

    var searchContactsInDialpad: Boolean
        get() = prefs.getBoolean(SEARCH_CONTACT_IN_DIALPAD, true)
        set(searchContactsInDialpad) = prefs.edit {
            putBoolean(SEARCH_CONTACT_IN_DIALPAD, searchContactsInDialpad)
        }

    var callerNotes: String
        get() = prefs.getString(CALLER_NOTES, "")!!
        set(callerNotes) = prefs.edit { putString(CALLER_NOTES, callerNotes) }

    fun parseCallerNotes(): ArrayList<CallerNote> {
        val notesType = object : TypeToken<List<CallerNote>>() {}.type
        val result = gson.fromJson<ArrayList<CallerNote>>(callerNotes, notesType) ?: mutableListOf<CallerNote>()
        return ArrayList(result)
    }

    var backPressedEndCall: Boolean
        get() = prefs.getBoolean(BACK_PRESSED_END_CALL, false)
        set(backPressedEndCall) = prefs.edit { putBoolean(BACK_PRESSED_END_CALL, backPressedEndCall) }

    var callBlockButton: Boolean
        get() = prefs.getBoolean(CALL_BLOCK_BUTTON, false)
        set(callBlockButton) = prefs.edit { putBoolean(CALL_BLOCK_BUTTON, callBlockButton) }

    var keepCallsInPopUp: Boolean
        get() = prefs.getBoolean(KEEP_CALLS_IN_POPUP, false)
        set(keepCallsInPopUp) = prefs.edit { putBoolean(KEEP_CALLS_IN_POPUP, keepCallsInPopUp) }

    var turnOnSpeakerInPopup: Boolean
        get() = prefs.getBoolean(TURN_ON_SPEAKER_IN_POPUP, false)
        set(turnOnSpeakerInPopup) = prefs.edit { putBoolean(TURN_ON_SPEAKER_IN_POPUP, turnOnSpeakerInPopup) }

    var initCallBlockingSetup: Boolean
        get() = prefs.getBoolean(INIT_CALL_BLOCKING_SETUP, true)
        set(initCallBlockingSetup) = prefs.edit { putBoolean(INIT_CALL_BLOCKING_SETUP, initCallBlockingSetup) }

    var recentOutgoingNumbers: MutableSet<String>
        get() = prefs.getStringSet(RECENT_OUTGOING_NUMBERS, hashSetOf(".")) as HashSet
        set(recentOutgoingNumbers) = prefs.edit {
            remove(RECENT_OUTGOING_NUMBERS).putStringSet(RECENT_OUTGOING_NUMBERS, recentOutgoingNumbers)
        }

    var onRecentClick: Int
        get() = prefs.getInt(ON_RECENT_CLICK, SWIPE_ACTION_CALL)
        set(onRecentClick) = prefs.edit { putInt(ON_RECENT_CLICK, onRecentClick) }

    var recentCallsFilterType: Int?
        get() {
            val value = prefs.getInt(RECENT_CALLS_FILTER_TYPE, -1)
            return if (value == -1) null else value
        }
        set(recentCallsFilterType) = prefs.edit {
            if (recentCallsFilterType == null) {
                putInt(RECENT_CALLS_FILTER_TYPE, -1)
            } else {
                putInt(RECENT_CALLS_FILTER_TYPE, recentCallsFilterType)
                // Save the last selected filter type
                putInt(RECENT_CALLS_LAST_FILTER_TYPE, recentCallsFilterType)
            }
        }

    var recentCallsLastFilterType: Int
        get() {
            val value = prefs.getInt(RECENT_CALLS_LAST_FILTER_TYPE, Calls.MISSED_TYPE)
            return if (value == -1) Calls.MISSED_TYPE else value
        }
        set(recentCallsLastFilterType) = prefs.edit {
            putInt(RECENT_CALLS_LAST_FILTER_TYPE, recentCallsLastFilterType)
        }

    var onContactClick: Int
        get() = prefs.getInt(ON_CONTACT_CLICK, SWIPE_ACTION_CALL)
        set(onContactClick) = prefs.edit { putInt(ON_CONTACT_CLICK, onContactClick) }

    var onFavoriteClick: Int
        get() = prefs.getInt(ON_FAVORITE_CLICK, SWIPE_ACTION_CALL)
        set(onContactClick) = prefs.edit { putInt(ON_FAVORITE_CLICK, onContactClick) }

    //Timer
    var timerMaxReminderSecs: Int
        get() = prefs.getInt(TIMER_MAX_REMINDER_SECS, DEFAULT_MAX_TIMER_REMINDER_SECS)
        set(timerMaxReminderSecs) = prefs.edit {
            putInt(TIMER_MAX_REMINDER_SECS, timerMaxReminderSecs)
        }

    //Swipe
    var swipeRightAction: Int
        get() = prefs.getInt(SWIPE_RIGHT_ACTION, SWIPE_ACTION_MESSAGE)
        set(swipeRightAction) = prefs.edit { putInt(SWIPE_RIGHT_ACTION, swipeRightAction) }

    var swipeLeftAction: Int
        get() = prefs.getInt(SWIPE_LEFT_ACTION, SWIPE_ACTION_DELETE)
        set(swipeLeftAction) = prefs.edit { putInt(SWIPE_LEFT_ACTION, swipeLeftAction) }

    var swipeVibration: Boolean
        get() = prefs.getBoolean(SWIPE_VIBRATION, true)
        set(swipeVibration) = prefs.edit { putBoolean(SWIPE_VIBRATION, swipeVibration) }

    var swipeRipple: Boolean
        get() = prefs.getBoolean(SWIPE_RIPPLE, false)
        set(swipeRipple) = prefs.edit { putBoolean(SWIPE_RIPPLE, swipeRipple) }

    // Auto Redial
    var autoRedialEnabled: Boolean
        get() = prefs.getBoolean(AUTO_REDIAL_ENABLED, false)
        set(autoRedialEnabled) = prefs.edit { putBoolean(AUTO_REDIAL_ENABLED, autoRedialEnabled) }

    var autoRedialMaxRetries: Int
        get() = prefs.getInt(AUTO_REDIAL_MAX_RETRIES, 3)
        set(autoRedialMaxRetries) = prefs.edit { putInt(AUTO_REDIAL_MAX_RETRIES, autoRedialMaxRetries) }

    var autoRedialDelayMs: Long
        get() = prefs.getLong(AUTO_REDIAL_DELAY_MS, 2000L)
        set(autoRedialDelayMs) = prefs.edit { putLong(AUTO_REDIAL_DELAY_MS, autoRedialDelayMs) }

    var shakeToAnswer: Boolean
        get() = prefs.getBoolean(SHAKE_TO_ANSWER, false)
        set(shakeToAnswer) = prefs.edit { putBoolean(SHAKE_TO_ANSWER, shakeToAnswer) }

    // Call Recording
    var callRecordingEnabled: Boolean
        get() = prefs.getBoolean(CALL_RECORDING_ENABLED, false)
        set(callRecordingEnabled) = prefs.edit { putBoolean(CALL_RECORDING_ENABLED, callRecordingEnabled) }

    var callRecordingFormat: Int
        get() = prefs.getInt(CALL_RECORDING_FORMAT, 0) // 0 = WAV (default)
        set(callRecordingFormat) = prefs.edit { putInt(CALL_RECORDING_FORMAT, callRecordingFormat) }

    var callRecordingAutoRule: Int
        get() = prefs.getInt(CALL_RECORDING_AUTO_RULE, RECORDING_RULE_ALL_CALLS) // All calls by default
        set(callRecordingAutoRule) = prefs.edit { putInt(CALL_RECORDING_AUTO_RULE, callRecordingAutoRule) }

    var recordingSaveLocation: Int
        get() = prefs.getInt(RECORDING_SAVE_LOCATION, 0) // 0 = App Files (default)
        set(recordingSaveLocation) = prefs.edit { putInt(RECORDING_SAVE_LOCATION, recordingSaveLocation) }

    var recordingCustomPath: String
        get() = prefs.getString(RECORDING_CUSTOM_PATH, "") ?: ""
        set(recordingCustomPath) = prefs.edit { putString(RECORDING_CUSTOM_PATH, recordingCustomPath) }

    var recordingFileNameTemplate: String
        get() = prefs.getString(RECORDING_FILE_NAME_TEMPLATE, "{timestamp}_{direction}_{phone_number}") 
            ?: "{timestamp}_{direction}_{phone_number}"
        set(recordingFileNameTemplate) = prefs.edit { putString(RECORDING_FILE_NAME_TEMPLATE, recordingFileNameTemplate) }

    // Auto Reply SMS
    var autoReplySmsEnabled: Boolean
        get() = prefs.getBoolean(AUTO_REPLY_SMS_ENABLED, false)
        set(autoReplySmsEnabled) = prefs.edit { putBoolean(AUTO_REPLY_SMS_ENABLED, autoReplySmsEnabled) }

    var autoReplySmsMessage: String
        get() = prefs.getString(AUTO_REPLY_SMS_MESSAGE, context.getString(R.string.auto_reply_sms_default_message)) 
            ?: context.getString(R.string.auto_reply_sms_default_message)
        set(autoReplySmsMessage) = prefs.edit { putString(AUTO_REPLY_SMS_MESSAGE, autoReplySmsMessage) }

    var autoReplySmsMissedCount: Int
        get() = prefs.getInt(AUTO_REPLY_SMS_MISSED_COUNT, 1) // Default: send after 1 missed call
        set(autoReplySmsMissedCount) = prefs.edit { putInt(AUTO_REPLY_SMS_MISSED_COUNT, autoReplySmsMissedCount) }
}

