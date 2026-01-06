package com.android.dialer.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.dialer.R
import com.android.dialer.helpers.Config

/**
 * Class for handling fee service via USSD codes for Koryo and Kangsong networks
 */
@SuppressLint("UnspecifiedRegisterReceiverFlag")
class CCFeeClass(
    private val context: Context,
    private val registerReceiver: Boolean = true
) {
    init {
        // Register this instance with FeeSMSReceiver so it can update fee info
        com.android.dialer.receivers.FeeSMSReceiver.setCCFeeClassInstance(this)
    }
    companion object {
        const val KORYO = "koryolink"
        const val KANGSONG = "KANGSONG NET"
        private const val TAG = "CCFeeClass"
        
        /**
         * Static method to call USSD code
         * Note: This method requires a valid context. Use the instance method callUssdCode instead.
         */
        @SuppressLint("MissingPermission")
        fun callUSSDCodeStatic(
            context: Context,
            ussdType: String,
            slotId: Int,
            listener: ActionFinishListener?
        ): Boolean {
            if (context == null) {
                return false
            }
            
            val resources = context.resources
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CALL_PHONE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }

            if (ussdType.contains(KORYO)) {
                val number = "*900*#"
                val subscriptionInfos = subManager.activeSubscriptionInfoList
                var simInfo: SubscriptionInfo? = null

                if (subscriptionInfos != null && subscriptionInfos.isNotEmpty()) {
                    simInfo = if (subscriptionInfos.size == 1) {
                        subscriptionInfos[0]
                    } else {
                        subscriptionInfos.getOrNull(slotId)
                    }
                }

                if (simInfo == null || simInfo.mnc != SimCardUtils.KORYO_NET) {
                    return false
                }

                val intent = Intent("com.mediatek.mms.appservice.USSD_PROCESSED").apply {
                    putExtra("ussd_flag", true)
                }
                context.sendBroadcast(intent)

                val blockIntent = Intent("com.chonha.total.action.ACTION_MESSAGE_BLOCK").apply {
                    setPackage("com.android.mms")
                    putExtra("messageBlock", 1)
                }
                context.sendBroadcast(blockIntent)

                val anotherTelephony = telephonyManager.createForSubscriptionId(simInfo.subscriptionId)
                anotherTelephony.sendUssdRequest(number, object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager,
                        request: String,
                        response: CharSequence
                    ) {
                        super.onReceiveUssdResponse(telephonyManager, request, response)
                        val responseStr = response.toString()
                        var strExpireDate = ""
                        Log.e(TAG, "onReceiveUssdResponse(KORYO): $responseStr")

                        var fCash = -1f
                        var nIdStart: Int
                        var nIdEnd: Int

                        if (responseStr.contains(resources.getString(R.string.koryo_cash_response_footer))) {
                            nIdEnd = responseStr.indexOf(resources.getString(R.string.koryo_cash_response_footer))
                            nIdStart = responseStr.indexOf(resources.getString(R.string.koryo_cash_response_header)) +
                                    resources.getString(R.string.koryo_cash_response_header).length
                            try {
                                fCash = responseStr.substring(nIdStart, nIdEnd).toFloat()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing cash", e)
                            }
                        }

                        if (responseStr.contains(resources.getString(R.string.koryo_date_response_footer))) {
                            nIdEnd = responseStr.indexOf(resources.getString(R.string.koryo_date_response_footer))
                            nIdStart = responseStr.indexOf(resources.getString(R.string.koryo_date_response_header)) +
                                    resources.getString(R.string.koryo_date_response_header).length
                            strExpireDate = responseStr.substring(nIdStart, nIdEnd)
                        }

                        if (fCash != -1f) {
                            FeeInfoUtils.setCash(context, slotId, fCash)
                            FeeInfoUtils.sendFeeInfoChange(context)
                        }

                        FeeInfoUtils.setFinishDate(context, slotId, strExpireDate)
                        listener?.onFinished()
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager,
                        request: String,
                        failureCode: Int
                    ) {
                        super.onReceiveUssdResponseFailed(telephonyManager, request, failureCode)
                        Log.e(TAG, "onReceiveUssdResponse(KORYO)-Failed: $failureCode")
                        listener?.onFinished()
                    }
                }, Handler(Looper.getMainLooper()))
            } else if (ussdType.contains(KANGSONG)) {
                val number = "*900#"
                val subscriptionInfos = subManager.activeSubscriptionInfoList
                var simInfo: SubscriptionInfo? = null

                if (subscriptionInfos != null && subscriptionInfos.isNotEmpty()) {
                    simInfo = if (subscriptionInfos.size == 1) {
                        subscriptionInfos[0]
                    } else {
                        subscriptionInfos.getOrNull(slotId)
                    }
                }

                if (simInfo == null || simInfo.mnc != SimCardUtils.KANGSONG_NET) {
                    return false
                }

                Log.e(TAG, "onReceiveUssdResponse(KANGSONG) - USSD Requested")
                val anotherTelephony = telephonyManager.createForSubscriptionId(simInfo.subscriptionId)
                anotherTelephony.sendUssdRequest(number, object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager,
                        request: String,
                        response: CharSequence
                    ) {
                        super.onReceiveUssdResponse(telephonyManager, request, response)
                        val responseStr = response.toString()
                        var strDate = ""
                        Log.e(TAG, "onReceiveUssdResponse(KANGSONG): $responseStr")

                        var fCash = -1f
                        var nIdStart: Int
                        var nIdEnd: Int

                        if (responseStr.contains(resources.getString(R.string.kangsong_cash_response_footer))) {
                            nIdEnd = responseStr.indexOf(resources.getString(R.string.kangsong_cash_response_footer))
                            nIdStart = responseStr.indexOf(resources.getString(R.string.kangsong_cash_response_header)) +
                                    resources.getString(R.string.kangsong_cash_response_header).length
                            try {
                                fCash = responseStr.substring(nIdStart, nIdEnd).toFloat()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing cash", e)
                            }
                        }

                        if (responseStr.contains(resources.getString(R.string.kangsong_date_response_footer))) {
                            nIdEnd = responseStr.indexOf(resources.getString(R.string.kangsong_date_response_footer))
                            nIdStart = responseStr.indexOf(resources.getString(R.string.kangsong_date_response_header)) +
                                    resources.getString(R.string.kangsong_date_response_header).length
                            strDate = responseStr.substring(nIdStart, nIdEnd)
                        }

                        if (fCash != -1f) {
                            FeeInfoUtils.setCash(context, slotId, fCash)
                            FeeInfoUtils.sendFeeInfoChange(context)
                        }

                        FeeInfoUtils.setFinishDate(context, slotId, strDate)
                        listener?.onFinished()
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager,
                        request: String,
                        failureCode: Int
                    ) {
                        super.onReceiveUssdResponseFailed(telephonyManager, request, failureCode)
                        Log.e(TAG, "onReceiveUssdResponse(KANGSONG)-Failed: $failureCode")
                        listener?.onFinished()
                    }
                }, Handler(Looper.getMainLooper()))
            }

            return true
        }
    }
    
    // Telephony intent actions
    private val ACTION_SIM_SLOT_SIM_MOUNT_CHANGE = "android.telephony.action.SIM_SLOT_SIM_MOUNT_CHANGE"
    private val ACTION_DEFAULT_SUBSCRIPTION_CHANGED = "android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED"

    private val appSetting: Config = Config.newInstance(context)
    private val resources: Resources = context.resources
    private val telephonyManager: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val subManager: SubscriptionManager =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

    private var bKR_USSD_Running = false
    private var bKS_USSD_Running = false

    private val simStateKey = "sim_state_value"

    private val mSimSlotReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return

            // Avoid SIM multi refresh
            var sim1State = ""
            var sim2State = ""

            try {
                val subscriptionInfos = subManager.activeSubscriptionInfoList
                if (subscriptionInfos != null && subscriptionInfos.isNotEmpty()) {
                    if (subscriptionInfos.size > 0) {
                        subscriptionInfos[0]?.let {
                            sim1State = it.iccId ?: ""
                        }
                    }
                    if (subscriptionInfos.size > 1) {
                        subscriptionInfos[1]?.let {
                            sim2State = it.iccId ?: ""
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting subscription info", e)
            }

            val currentSimState = "[$sim1State][$sim2State]"
            val savedSimState = context.getSharedPreferences("fee_info_prefs", Context.MODE_PRIVATE)
                .getString(simStateKey, "")

            if (TextUtils.equals(currentSimState, savedSimState)) {
                return
            }

            context.getSharedPreferences("fee_info_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString(simStateKey, currentSimState)
                .apply()

            Log.e(TAG, "SIM_SLOT_CHANGED")
            if (action == ACTION_SIM_SLOT_SIM_MOUNT_CHANGE ||
                action == ACTION_DEFAULT_SUBSCRIPTION_CHANGED
            ) {
                val listener = object : ActionFinishListener {
                    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
                    override fun onFinished() {
                        // Determine which SIMs are active
                        val subscriptionInfos = subManager.activeSubscriptionInfoList
                        if (subscriptionInfos != null && subscriptionInfos.isNotEmpty()) {
                            val sim1 = subscriptionInfos.firstOrNull()
                            val sim2 = subscriptionInfos.getOrNull(1)

                            val sim1Mnc = sim1?.mnc ?: -1
                            val sim2Mnc = sim2?.mnc ?: -1

                            if (sim1Mnc != -1 && sim2Mnc != -1) {
                                // Dual SIM
                                Log.e(TAG, "SimCount = 2")
                                Handler(Looper.getMainLooper()).postDelayed({
                                    val ussdType1 = if (sim1Mnc == SimCardUtils.KORYO_NET) KORYO else KANGSONG
                                    callUssdCode(ussdType1, false, 0, object : ActionFinishListener {
                                        override fun onFinished() {
                                            Handler(Looper.getMainLooper()).postDelayed({
                                                val ussdType2 = if (sim2Mnc == SimCardUtils.KORYO_NET) KORYO else KANGSONG
                                                callUssdCode(ussdType2, false, 1, null)
                                            }, 0)
                                        }
                                    })
                                }, 0)
                            } else {
                                // Single SIM
                                Log.e(TAG, "SimCount = 1")
                                val activeSimMnc = if (sim1Mnc != -1) sim1Mnc else sim2Mnc
                                val slotId = if (sim1Mnc != -1) 0 else 1

                                if (activeSimMnc == SimCardUtils.KANGSONG_NET) {
                                    Log.e(TAG, "DefaultSim = KANGSONG")
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        callUssdCode(KANGSONG, false, slotId, null)
                                    }, 0)
                                } else if (activeSimMnc == SimCardUtils.KORYO_NET) {
                                    Log.e(TAG, "DefaultSim = KORYO")
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        callUssdCode(KORYO, false, slotId, null)
                                    }, 5000)
                                }
                            }
                        }
                    }
                }
                SimCardUtils.dualSimSupported(context, listener)
            }
        }
    }

    init {
        if (registerReceiver) {
            val intentFilter1 = IntentFilter(ACTION_SIM_SLOT_SIM_MOUNT_CHANGE)
            context.registerReceiver(mSimSlotReceiver, intentFilter1)

            val intentFilter2 = IntentFilter(ACTION_DEFAULT_SUBSCRIPTION_CHANGED)
            context.registerReceiver(mSimSlotReceiver, intentFilter2)
        }
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(mSimSlotReceiver)
            // Clear the reference when unregistering
            com.android.dialer.receivers.FeeSMSReceiver.setCCFeeClassInstance(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    fun callUssdCode(
        ussdType: String,
        byUser: Boolean,
        slotId: Int,
        listener: ActionFinishListener?
    ) {
        if (ussdType.contains(KORYO) && bKR_USSD_Running) return
        if (ussdType.contains(KANGSONG) && bKS_USSD_Running) return

        try {
            _callUSSDCode(ussdType, byUser, slotId, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error calling USSD code", e)
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun _callUSSDCode(
        ussdType: String,
        byUser: Boolean,
        slotId: Int,
        listener: ActionFinishListener?
    ): Boolean {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        // USSD dialog will be shown if byUser is true
        var ussdDlg: Dialog? = null
        if (byUser) {
            try {
                ussdDlg = Dialog(context)
                // Create a simple loading dialog
                val textView = TextView(context).apply {
                    text = "Loading..."
                    setPadding(50, 30, 50, 30)
                }
                ussdDlg?.setContentView(textView)
                ussdDlg?.setCancelable(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up USSD dialog", e)
            }
        }

        if (ussdType.contains(KORYO)) {
            val number = "*900*#"
            val subscriptionInfos = subManager.activeSubscriptionInfoList
            var simInfo: SubscriptionInfo? = null

            if (subscriptionInfos != null && subscriptionInfos.isNotEmpty()) {
                simInfo = if (subscriptionInfos.size == 1) {
                    subscriptionInfos[0]
                } else {
                    subscriptionInfos.getOrNull(slotId)
                }
            }

            if (simInfo == null || simInfo.mnc != SimCardUtils.KORYO_NET) {
                return false
            }

            // Send broadcast for USSD processing
            val intent = Intent("com.mediatek.mms.appservice.USSD_PROCESSED").apply {
                putExtra("ussd_flag", true)
            }
            context.sendBroadcast(intent)

            Log.e(TAG, "onReceiveUssdResponse(KORYO) - USSD Requested")

            if (byUser) {
                try {
                    ussdDlg?.show()
                } catch (e: Exception) {
                    Log.d(TAG, "USSDUI Error: ${e.message}")
                }
            }

            // Block message notification for automatic requests
            val blockIntent = Intent("com.chonha.total.action.ACTION_MESSAGE_BLOCK").apply {
                setPackage("com.android.mms")
                putExtra("messageBlock", if (byUser) 0 else 1)
            }
            context.sendBroadcast(blockIntent)

            val anotherTelephony = telephonyManager.createForSubscriptionId(simInfo.subscriptionId)
            anotherTelephony.sendUssdRequest(number, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    telephonyManager: TelephonyManager,
                    request: String,
                    response: CharSequence
                ) {
                    super.onReceiveUssdResponse(telephonyManager, request, response)
                    try {
                        ussdDlg?.dismiss()
                    } catch (e: Exception) {
                        // Ignore
                    }

                    val responseStr = response.toString()
                    var strExpireDate = ""
                    Log.e(TAG, "onReceiveUssdResponse(KORYO): $responseStr")

                    if (byUser) {
                        showResponseDialog(responseStr)
                    }

                    var fCash = -1f
                    var nIdStart: Int
                    var nIdEnd: Int

                    if (responseStr.contains(resources.getString(R.string.koryo_cash_response_footer))) {
                        nIdEnd = responseStr.indexOf(resources.getString(R.string.koryo_cash_response_footer))
                        nIdStart = responseStr.indexOf(resources.getString(R.string.koryo_cash_response_header)) +
                                resources.getString(R.string.koryo_cash_response_header).length
                        try {
                            fCash = responseStr.substring(nIdStart, nIdEnd).toFloat()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing cash", e)
                        }
                    }

                    if (responseStr.contains(resources.getString(R.string.koryo_date_response_footer))) {
                        nIdEnd = responseStr.indexOf(resources.getString(R.string.koryo_date_response_footer))
                        nIdStart = responseStr.indexOf(resources.getString(R.string.koryo_date_response_header)) +
                                resources.getString(R.string.koryo_date_response_header).length
                        strExpireDate = responseStr.substring(nIdStart, nIdEnd)
                    }

                    // Save fee info
                    if (fCash != -1f) {
                        FeeInfoUtils.setCash(context, slotId, fCash)
                    }

                    FeeInfoUtils.setFinishDate(context, slotId, strExpireDate)
                    bKR_USSD_Running = false
                    FeeInfoUtils.sendFeeInfoChange(context)

                    listener?.onFinished()
                }

                override fun onReceiveUssdResponseFailed(
                    telephonyManager: TelephonyManager,
                    request: String,
                    failureCode: Int
                ) {
                    super.onReceiveUssdResponseFailed(telephonyManager, request, failureCode)
                    try {
                        ussdDlg?.dismiss()
                    } catch (e: Exception) {
                        // Ignore
                    }
                    Log.e(TAG, "onReceiveUssdResponse(KORYO)-Failed: $failureCode")
                    bKR_USSD_Running = false
                    listener?.onFinished()
                }
            }, Handler(Looper.getMainLooper()))

            bKR_USSD_Running = true
        } else if (ussdType.contains(KANGSONG)) {
            val number = "*900#"
            val subscriptionInfos = subManager.activeSubscriptionInfoList
            var simInfo: SubscriptionInfo? = null

            if (subscriptionInfos != null && subscriptionInfos.isNotEmpty()) {
                if (subscriptionInfos.size == 1) {
                    simInfo = subscriptionInfos[0]
                } else {
                    simInfo = subscriptionInfos.getOrNull(slotId)
                }
            }

            if (simInfo == null || simInfo.mnc != SimCardUtils.KANGSONG_NET) {
                return false
            }

            if (byUser) {
                try {
                    ussdDlg?.show()
                } catch (e: Exception) {
                    Log.d(TAG, "USSDUI Error: ${e.message}")
                }
            }

            Log.e(TAG, "onReceiveUssdResponse(KANGSONG) - USSD Requested")
            val anotherTelephony = telephonyManager.createForSubscriptionId(simInfo.subscriptionId)
            anotherTelephony.sendUssdRequest(number, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    telephonyManager: TelephonyManager,
                    request: String,
                    response: CharSequence
                ) {
                    super.onReceiveUssdResponse(telephonyManager, request, response)
                    try {
                        ussdDlg?.dismiss()
                    } catch (e: Exception) {
                        // Ignore
                    }

                    val responseStr = response.toString()
                    var strDate = ""
                    Log.e(TAG, "onReceiveUssdResponse(KANGSONG): $responseStr")

                    if (byUser) {
                        showResponseDialog(responseStr)
                    }

                    var fCash = -1f
                    var nIdStart: Int
                    var nIdEnd: Int

                    if (responseStr.contains(resources.getString(R.string.kangsong_cash_response_footer))) {
                        nIdEnd = responseStr.indexOf(resources.getString(R.string.kangsong_cash_response_footer))
                        nIdStart = responseStr.indexOf(resources.getString(R.string.kangsong_cash_response_header)) +
                                resources.getString(R.string.kangsong_cash_response_header).length
                        try {
                            fCash = responseStr.substring(nIdStart, nIdEnd).toFloat()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing cash", e)
                        }
                    }

                    if (responseStr.contains(resources.getString(R.string.kangsong_date_response_footer))) {
                        nIdEnd = responseStr.indexOf(resources.getString(R.string.kangsong_date_response_footer))
                        nIdStart = responseStr.indexOf(resources.getString(R.string.kangsong_date_response_header)) +
                                resources.getString(R.string.kangsong_date_response_header).length
                        strDate = responseStr.substring(nIdStart, nIdEnd)
                    }

                    bKS_USSD_Running = false

                    // Save fee info
                    if (fCash != -1f) {
                        FeeInfoUtils.setCash(context, slotId, fCash)
                        FeeInfoUtils.sendFeeInfoChange(context)
                    }

                    FeeInfoUtils.setFinishDate(context, slotId, strDate)
                    listener?.onFinished()
                }

                override fun onReceiveUssdResponseFailed(
                    telephonyManager: TelephonyManager,
                    request: String,
                    failureCode: Int
                ) {
                    super.onReceiveUssdResponseFailed(telephonyManager, request, failureCode)
                    try {
                        ussdDlg?.dismiss()
                    } catch (e: Exception) {
                        // Ignore
                    }
                    Log.e(TAG, "onReceiveUssdResponse(KANGSONG)-Failed: $failureCode")
                    bKS_USSD_Running = false
                    listener?.onFinished()
                }
            }, Handler(Looper.getMainLooper()))

            bKS_USSD_Running = true
        }

        return true
    }

    private fun showResponseDialog(responseStr: String) {
        try {
            // Create a simple dialog with the response text
            val dialog = android.app.AlertDialog.Builder(context)
                .setMessage(responseStr)
                .setPositiveButton(R.string.confirm_dialog_allow_button) { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(true)
                .create()

            dialog.setOnCancelListener {
                if (!registerReceiver) {
                    // If not registered, finish the activity if it's a widget activity
                    // This would need to be adapted based on your widget implementation
                }
            }

            dialog.show()
        } catch (e: Exception) {
            Log.d(TAG, "USSDResponse Error: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun onReceived919SMS(nMinute: Int, nMinuteMonth: Int, nSms: Int, nBytes: Int, subId: Int) {
        val slotId = SimCardUtils.getSlotIdUsingSubId(subId)
        Log.e(TAG, "onReceived919SMS: KoryoSlotId=$slotId")

        FeeInfoUtils.setMinute(context, slotId, nMinute)
        FeeInfoUtils.setRemainMinute(context, slotId, nMinuteMonth)
        FeeInfoUtils.setSms(context, slotId, nSms)
        FeeInfoUtils.setByte(context, slotId, nBytes)

        FeeInfoUtils.sendFeeInfoChange(context)
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    fun getSimCount(): Int {
        return try {
            subManager.activeSubscriptionInfoList?.size ?: 0
        } catch (e: Exception) {
            0
        }
    }
}

