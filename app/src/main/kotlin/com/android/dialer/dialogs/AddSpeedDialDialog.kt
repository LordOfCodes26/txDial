package com.android.dialer.dialogs

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.*
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
    private var isDismissed = false

    init {
        val binding = DialogAddSpeedDialBinding.inflate(activity.layoutInflater)
        
        // Setup BlurView with the provided BlurTarget
        val blurView = binding.root.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)
        
        // Setup title inside BlurView
        val titleTextView = binding.root.findViewById<com.goodwy.commons.views.MyTextView>(R.id.dialog_title)
        titleTextView?.apply {
            beVisible()
            text = activity.getString(R.string.speed_dial)
        }
        
        binding.apply {
            addSpeedDialEditText.apply {
                setText(speedDial.number)
                hint = speedDial.number
            }
        }

        val primaryColor = activity.getProperPrimaryColor()
        
        activity.getAlertDialogBuilder()
            .apply {
                // Pass empty titleText to prevent setupDialogStuff from adding title outside BlurView
                activity.setupDialogStuff(binding.root, this, titleText = "") { alertDialog ->
                    dialog = alertDialog
                    alertDialog.showKeyboard(binding.addSpeedDialEditText)
                    
                    // Setup buttons inside BlurView
                    val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
                    val negativeButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
                    val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)
                    
                    // Ensure buttons container is visible
                    buttonsContainer?.visibility = android.view.View.VISIBLE
                    
                    positiveButton?.apply {
                        setTextColor(primaryColor)
                        setOnClickListener {
                            if (isDismissed) return@setOnClickListener
                            isDismissed = true
                            val newTitle = binding.addSpeedDialEditText.text?.toString() ?: ""
                            // Dismiss dialog first, then call callback on next UI cycle to prevent re-showing
                            alertDialog.dismiss()
                            binding.root.post {
                                callback(newTitle)
                            }
                        }
                    }
                    
                    negativeButton?.apply {
                        beVisible()
                        setTextColor(primaryColor)
                        setOnClickListener {
                            if (isDismissed) return@setOnClickListener
                            isDismissed = true
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }
}
