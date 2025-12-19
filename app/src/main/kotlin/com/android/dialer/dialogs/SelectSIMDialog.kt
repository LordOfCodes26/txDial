package com.android.dialer.dialogs

import android.annotation.SuppressLint
import android.telecom.PhoneAccountHandle
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.databinding.RadioButtonIconBinding
import com.goodwy.commons.extensions.*
import com.android.dialer.R
import com.android.dialer.databinding.DialogSelectSimBinding
import com.android.dialer.extensions.config
import com.android.dialer.extensions.getAvailableSIMCardLabels
import douglasspgyn.com.github.circularcountdown.CircularCountdown
import douglasspgyn.com.github.circularcountdown.listener.CircularListener
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

@SuppressLint("MissingPermission", "SetTextI18n")
class SelectSIMDialog(
    val activity: BaseSimpleActivity,
    val phoneNumber: String,
    onDismiss: () -> Unit = {},
    blurTarget: BlurTarget,
    val callback: (handle: PhoneAccountHandle?, label: String?) -> Unit
) {
    private var dialog: AlertDialog? = null
    private val binding by activity.viewBinding(DialogSelectSimBinding::inflate)

    init {
        // Setup BlurView with the provided BlurTarget
        val blurView = binding.root.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(5f)
            ?.setBlurAutoUpdate(true)
        val isManageSpeedDial = phoneNumber == ""
        binding.selectSimLabel.beGoneIf(isManageSpeedDial)
        binding.divider.root.beGoneIf(isManageSpeedDial)
        binding.selectSimRememberHolder.beGoneIf(isManageSpeedDial)
        binding.selectSimRememberHolder.setOnClickListener {
            binding.selectSimRemember.toggle()
        }

        activity.getAvailableSIMCardLabels().forEachIndexed { index, SIMAccount ->
            val radioButton = RadioButtonIconBinding.inflate(activity.layoutInflater, null, false).apply {
                val indexText = index + 1
                dialogRadioButton.apply {
                    text = "$indexText - ${SIMAccount.label}"
                    id = index
                    setOnClickListener { selectedSIM(SIMAccount.handle, SIMAccount.label) }
                }
                dialogRadioButtonIcon.apply {
                    val res = when (indexText) {
                        1 -> R.drawable.ic_sim_one
                        2 -> R.drawable.ic_sim_two
                        else -> R.drawable.ic_sim_vector
                    }
                    val drawable = ResourcesCompat.getDrawable(resources, res, activity.theme)?.apply {
                        applyColorFilter(SIMAccount.color)
                    }
                    setImageDrawable(drawable)
                }
            }
            binding.selectSimRadioGroup.addView(radioButton.root, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        activity.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    dialog = alertDialog
                    // Make dialog backdrop transparent
                    alertDialog.window?.setDimAmount(0f)
                }
            }

        dialog?.setOnDismissListener {
            cancelCountdown()
            onDismiss()
        }

        // Start auto-select countdown if enabled
        if (activity.config.autoSimSelectEnabled) {
            startAutoSelectCountdown()
        } else {
            binding.countdownView.beGone()
        }
    }

    private fun startAutoSelectCountdown() {
        val simList = activity.getAvailableSIMCardLabels()
        if (simList.isEmpty()) return

        val selectedIndex = activity.config.autoSimSelectIndex.coerceIn(0, simList.size - 1)
        val selectedSIM = simList[selectedIndex]
        val delaySeconds = activity.config.autoSimSelectDelaySeconds

        binding.countdownView.beVisible()
        binding.countdownView.create(delaySeconds, delaySeconds, CircularCountdown.TYPE_SECOND)
            .listener(object : CircularListener {
                override fun onTick(progress: Int) {
                    // Progress update - can be used for additional UI updates if needed
                }

                override fun onFinish(newCycle: Boolean, cycleCount: Int) {
                    // Auto-select the SIM when countdown completes
                    selectedSIM(selectedSIM.handle, selectedSIM.label)
                }
            })
            .start()
    }

    private fun cancelCountdown() {
        binding.countdownView.stop()
    }

    private fun selectedSIM(handle: PhoneAccountHandle, label: String) {
        cancelCountdown()
        if (binding.selectSimRemember.isChecked) {
            activity.config.saveCustomSIM(phoneNumber, handle)
        }

        callback(handle, label)
        dialog?.dismiss()
    }
}
