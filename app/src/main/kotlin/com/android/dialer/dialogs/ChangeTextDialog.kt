package com.android.dialer.dialogs

import android.annotation.SuppressLint
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import com.goodwy.commons.extensions.*
import com.android.dialer.R
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.databinding.DialogChangeTextBinding
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

@SuppressLint("SetTextI18n")
class ChangeTextDialog(
    val activity: SimpleActivity,
    val title: String = activity.getString(R.string.quick_answers),
    val currentText: String?,
    val maxLength: Int = 0,
    val showNeutralButton: Boolean = false,
    val neutralTextRes: Int = com.goodwy.commons.R.string.use_default,
    blurTarget: BlurTarget,
    val callback: (newText: String) -> Unit) {

    init {
        val binding = DialogChangeTextBinding.inflate(activity.layoutInflater)
        val view = binding.root
        
        // Setup BlurView with the provided BlurTarget
        val blurView = view.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)
        
        // Setup title inside BlurView
        val titleTextView = view.findViewById<com.goodwy.commons.views.MyTextView>(R.id.dialog_title)
        titleTextView?.apply {
            beVisible()
            text = title
        }
        
        binding.text.apply {

            if (maxLength > 0) {
                binding.count.beVisible()
                val filterArray = arrayOfNulls<InputFilter>(1)
                filterArray[0] = LengthFilter(maxLength)
                filters = filterArray
                onTextChangeListener {
                    val length = it.length
                    binding.count.text = "$length/$maxLength"
                }
            }

            setText(currentText)
        }

        val primaryColor = activity.getProperPrimaryColor()
        
        activity.getAlertDialogBuilder()
            .apply {
                // Pass empty titleText to prevent setupDialogStuff from adding title outside BlurView
                activity.setupDialogStuff(view, this, titleText = "") { alertDialog ->
                    alertDialog.showKeyboard(binding.text)
                    
                    // Setup buttons inside BlurView
                    val positiveButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
                    val negativeButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
                    val neutralButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.neutral_button)
                    val buttonsContainer = view.findViewById<android.widget.LinearLayout>(R.id.buttons_container)
                    
                    buttonsContainer?.visibility = android.view.View.VISIBLE
                    
                    positiveButton?.apply {
                        setTextColor(primaryColor)
                        setOnClickListener {
                            val text = binding.text.value
                            alertDialog.dismiss()
                            callback(text)
                        }
                    }
                    
                    negativeButton?.apply {
                        beVisible()
                        setTextColor(primaryColor)
                        setOnClickListener {
                            alertDialog.dismiss()
                        }
                    }
                    
                    neutralButton?.apply {
                        beVisibleIf(showNeutralButton)
                        text = activity.getString(neutralTextRes)
                        setTextColor(primaryColor)
                        setOnClickListener {
                            val text = ""
                            alertDialog.dismiss()
                            callback(text)
                        }
                    }
                }
            }
    }
}
