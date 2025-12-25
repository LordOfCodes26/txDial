package com.android.dialer.dialogs

import com.goodwy.commons.extensions.*
import com.android.dialer.R
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.databinding.DialogExportCallHistoryBinding
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class ExportCallHistoryDialog(val activity: SimpleActivity, blurTarget: BlurTarget, callback: (filename: String) -> Unit) {

    init {
        val binding = DialogExportCallHistoryBinding.inflate(activity.layoutInflater)
        
        // Setup BlurView with the provided BlurTarget
        val blurView = binding.root.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)
        
        binding.apply {
            exportCallHistoryFilename.setText("call_history_${getCurrentFormattedDateTime()}")
        }

        // Setup title inside BlurView
        val titleTextView = binding.root.findViewById<com.goodwy.commons.views.MyTextView>(R.id.dialog_title)
        titleTextView?.apply {
            beVisible()
            text = activity.resources.getString(R.string.export_call_history)
        }

        activity.getAlertDialogBuilder().apply {
            // Pass empty titleText to prevent setupDialogStuff from adding title outside BlurView
            activity.setupDialogStuff(binding.root, this, titleText = "") { alertDialog ->
                // Setup custom buttons inside BlurView
                val primaryColor = activity.getProperPrimaryColor()
                val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
                val negativeButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
                val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)

                buttonsContainer?.visibility = android.view.View.VISIBLE

                positiveButton?.apply {
                    visibility = android.view.View.VISIBLE
                    setTextColor(primaryColor)
                    setOnClickListener {
                        val filename = binding.exportCallHistoryFilename.value
                        when {
                            filename.isEmpty() -> activity.toast(R.string.empty_name)
                            filename.isAValidFilename() -> {
                                callback(filename)
                                alertDialog.dismiss()
                            }
                            else -> activity.toast(R.string.invalid_name)
                        }
                    }
                }

                negativeButton?.apply {
                    visibility = android.view.View.VISIBLE
                    setTextColor(primaryColor)
                    setOnClickListener {
                        alertDialog.dismiss()
                    }
                }
            }
        }
    }
}
