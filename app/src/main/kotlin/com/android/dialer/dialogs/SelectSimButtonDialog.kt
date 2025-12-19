package com.android.dialer.dialogs

import android.telecom.PhoneAccountHandle
import androidx.appcompat.app.AlertDialog
import douglasspgyn.com.github.circularcountdown.CircularCountdown
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.*
import com.android.dialer.R
import com.android.dialer.databinding.DialogSelectSimButtonBinding
import com.android.dialer.extensions.config
import com.android.dialer.extensions.getAvailableSIMCardLabels
import douglasspgyn.com.github.circularcountdown.listener.CircularListener
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class SelectSimButtonDialog(
    val activity: BaseSimpleActivity,
    val phoneNumber: String,
    onDismiss: () -> Unit = {},
    blurTarget: BlurTarget,
    val callback: (handle: PhoneAccountHandle?, label: String?) -> Unit
) {
    private var dialog: AlertDialog? = null
    private val binding by activity.viewBinding(DialogSelectSimButtonBinding::inflate)

    init {
        // Setup BlurView with the provided BlurTarget
        val blurView = binding.root.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setOverlayColor(0xa3ffffff.toInt())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)

        val textColor = activity.baseConfig.simIconsColors[1].getContrastColor()

        activity.getAvailableSIMCardLabels().forEachIndexed { index, SIMAccount ->
            val indexText = index + 1
            if (indexText == 1) {
                binding.sim1Button.apply {
                    val drawable = resources.getColoredDrawableWithColor(activity, R.drawable.button_gray_bg, activity.baseConfig.simIconsColors[1])
                    background = drawable
                    setPadding(2, 2, 2, 2)
                    setTextColor(textColor)
                    setOnClickListener { selectedSIM(SIMAccount.handle, SIMAccount.label) }
                }
            } else if (indexText == 2) {
                binding.sim2Button.apply {
                    val drawable = resources.getColoredDrawableWithColor(activity, R.drawable.button_gray_bg, activity.baseConfig.simIconsColors[2])
                    background = drawable
                    setPadding(2,2,2,2)
                    setTextColor(textColor)
                    setOnClickListener { selectedSIM(SIMAccount.handle, SIMAccount.label) }
                }
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                val title = activity.getString(R.string.select_sim)
                activity.setupDialogStuff(binding.root, this, titleText = title) { alertDialog ->
                    dialog = alertDialog
                }
            }

        binding.cancelButton.apply {
            val drawable = resources.getColoredDrawableWithColor(activity, R.drawable.button_gray_bg, 0xFFEB5545.toInt())
            background = drawable
            setPadding(2,2,2,2)
            setTextColor(textColor)
            setOnClickListener {
                dialog?.dismiss()
            }
        }

        binding.selectSimRememberHolder.setOnClickListener {
            binding.selectSimRemember.toggle()
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
