package com.android.dialer.dialogs

import android.view.View
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.android.dialer.R
import com.android.dialer.databinding.DialogChangeSortingBinding
import com.android.dialer.extensions.config
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class ChangeSortingDialog(val activity: BaseSimpleActivity, private val showCustomSorting: Boolean = false, blurTarget: BlurTarget, private val callback: () -> Unit) {
    private val binding by activity.viewBinding(DialogChangeSortingBinding::inflate)

    private var currSorting = 0
    private var config = activity.config

    init {
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
            text = activity.getString(R.string.sort_by)
        }
        
        val primaryColor = activity.getProperPrimaryColor()
        
        activity.getAlertDialogBuilder()
            .apply {
                // Pass empty titleText to prevent setupDialogStuff from adding title outside BlurView
                activity.setupDialogStuff(binding.root, this, titleText = "") { alertDialog ->
                    // Setup buttons inside BlurView
                    val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
                    val negativeButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
                    val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)
                    
                    buttonsContainer?.visibility = android.view.View.VISIBLE
                    
                    positiveButton?.apply {
                        setTextColor(primaryColor)
                        setOnClickListener {
                            dialogConfirmed()
                            alertDialog.dismiss()
                        }
                    }
                    
                    negativeButton?.apply {
                        beVisible()
                        setTextColor(primaryColor)
                        setOnClickListener {
                            alertDialog.dismiss()
                        }
                    }
                }
            }

        currSorting = if (showCustomSorting && config.isCustomOrderSelected) {
            SORT_BY_CUSTOM
        } else {
            config.sorting
        }

        setupSortRadio()
        setupOrderRadio()
        setupSymbolsFirst()
    }

    private fun setupSortRadio() {
        binding.apply {
            sortingDialogRadioSorting.setOnCheckedChangeListener { group, checkedId ->
                val isCustomSorting = checkedId == sortingDialogRadioCustom.id
                sortingDialogRadioOrder.beGoneIf(isCustomSorting)
                sortingDialogSymbolsFirstCheckbox.beGoneIf(isCustomSorting)
                divider.root.beGoneIf(isCustomSorting)
            }

            val sortBtn = when {
                currSorting and SORT_BY_FIRST_NAME != 0 -> sortingDialogRadioFirstName
                currSorting and SORT_BY_MIDDLE_NAME != 0 -> sortingDialogRadioMiddleName
                currSorting and SORT_BY_SURNAME != 0 -> sortingDialogRadioSurname
                currSorting and SORT_BY_FULL_NAME != 0 -> sortingDialogRadioFullName
                currSorting and SORT_BY_CUSTOM != 0 -> sortingDialogRadioCustom
                else -> sortingDialogRadioDateCreated
            }
            sortBtn.isChecked = true

            if (showCustomSorting) {
                sortingDialogRadioCustom.isChecked = config.isCustomOrderSelected
            }
            sortingDialogRadioCustom.beGoneIf(!showCustomSorting)
        }
    }

    private fun setupOrderRadio() {
        var orderBtn = binding.sortingDialogRadioAscending

        if (currSorting and SORT_DESCENDING != 0) {
            orderBtn = binding.sortingDialogRadioDescending
        }
        orderBtn.isChecked = true
    }

    private fun setupSymbolsFirst() {
        binding.sortingDialogSymbolsFirstCheckbox.isChecked = config.sortingSymbolsFirst
    }

    private fun dialogConfirmed() {
        var sorting = when (binding.sortingDialogRadioSorting.checkedRadioButtonId) {
            R.id.sorting_dialog_radio_first_name -> SORT_BY_FIRST_NAME
            R.id.sorting_dialog_radio_middle_name -> SORT_BY_MIDDLE_NAME
            R.id.sorting_dialog_radio_surname -> SORT_BY_SURNAME
            R.id.sorting_dialog_radio_full_name -> SORT_BY_FULL_NAME
            R.id.sorting_dialog_radio_custom -> SORT_BY_CUSTOM
            else -> SORT_BY_DATE_CREATED
        }

        if (sorting != SORT_BY_CUSTOM && binding.sortingDialogRadioOrder.checkedRadioButtonId == R.id.sorting_dialog_radio_descending) {
            sorting = sorting or SORT_DESCENDING
        }

        if (showCustomSorting) {
            if (sorting == SORT_BY_CUSTOM) {
                config.isCustomOrderSelected = true
            } else {
                config.isCustomOrderSelected = false
                config.sorting = sorting
            }
        } else {
            config.sorting = sorting
        }

        config.sortingSymbolsFirst = binding.sortingDialogSymbolsFirstCheckbox.isChecked

        callback()
    }
}
