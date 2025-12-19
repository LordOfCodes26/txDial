package com.android.dialer.dialogs

import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.helpers.TAB_CALL_HISTORY
import com.goodwy.commons.helpers.TAB_CONTACTS
import com.goodwy.commons.helpers.TAB_FAVORITES
import com.goodwy.commons.views.MyAppCompatCheckbox
import com.android.dialer.R
import com.android.dialer.databinding.DialogManageVisibleTabsBinding
import com.android.dialer.extensions.config
import com.android.dialer.helpers.ALL_TABS_MASK
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class ManageVisibleTabsDialog(val activity: BaseSimpleActivity, blurTarget: BlurTarget) {
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private val binding by activity.viewBinding(DialogManageVisibleTabsBinding::inflate)
    private val tabs = LinkedHashMap<Int, Int>()

    init {
        // Setup BlurView with the provided BlurTarget
        val blurView = binding.root.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(5f)
            ?.setBlurAutoUpdate(true)
        
        // Add title inside BlurView
        val titleView = binding.root.findViewById<com.goodwy.commons.views.MyTextView>(R.id.dialog_title)
        titleView?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(R.string.manage_shown_tabs)
        }
        
        tabs.apply {
            put(TAB_FAVORITES, R.id.manage_visible_tabs_favorites)
            put(TAB_CALL_HISTORY, R.id.manage_visible_tabs_call_history)
            put(TAB_CONTACTS, R.id.manage_visible_tabs_contacts)
        }

        val showTabs = activity.config.showTabs
        for ((key, value) in tabs) {
            binding.root.findViewById<MyAppCompatCheckbox>(value).isChecked = showTabs and key != 0
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId = 0) { alertDialog ->
                    dialog = alertDialog
                    
                    // Setup custom buttons inside BlurView after dialog is created
                    val primaryColor = activity.getProperPrimaryColor()
                    val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
                    val negativeButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
                    val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)
                    
                    buttonsContainer?.visibility = android.view.View.VISIBLE
                    
                    positiveButton?.apply {
                        visibility = android.view.View.VISIBLE
                        text = activity.resources.getString(R.string.ok)
                        setTextColor(primaryColor)
                        setOnClickListener { dialogConfirmed() }
                    }
                    
                    negativeButton?.apply {
                        visibility = android.view.View.VISIBLE
                        text = activity.resources.getString(R.string.cancel)
                        setTextColor(primaryColor)
                        setOnClickListener { dialog?.dismiss() }
                    }
                }
            }
    }

    private fun dialogConfirmed() {
        var result = 0
        for ((key, value) in tabs) {
            if (binding.root.findViewById<MyAppCompatCheckbox>(value).isChecked) {
                result += key
            }
        }

        if (result == 0) {
            result = ALL_TABS_MASK
        }

        activity.config.showTabs = result
        dialog?.dismiss()
    }
}
