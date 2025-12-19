package com.android.dialer.dialogs

import android.app.Activity
import android.content.DialogInterface.BUTTON_POSITIVE
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.showKeyboard
import com.android.dialer.R
import com.android.dialer.databinding.DialogAddSpeedDialBinding
import com.android.dialer.models.SpeedDial
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class AddSpeedDialDialog(
    private val activity: Activity,
    private val speedDial: SpeedDial,
    blurTarget: BlurTarget,
    private val callback: (name: String) -> Unit,
) {
    private var dialog: AlertDialog? = null

    init {
        val binding = DialogAddSpeedDialBinding.inflate(activity.layoutInflater)
        
        // Setup BlurView with the provided BlurTarget
        val blurView = binding.root.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(5f)
            ?.setBlurAutoUpdate(true)
        
        binding.apply {
            addSpeedDialEditText.apply {
                setText(speedDial.number)
                hint = speedDial.number
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok, null)
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.speed_dial) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.showKeyboard(binding.addSpeedDialEditText)
                    alertDialog.getButton(BUTTON_POSITIVE).apply {
                        setOnClickListener {
                            val newTitle = binding.addSpeedDialEditText.text.toString()
                            callback(newTitle)
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }
}
