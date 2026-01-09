package com.android.dialer.dialogs

import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.*
import com.android.dialer.R
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.adapters.FilterCallTypesAdapter
import com.android.dialer.adapters.CallTypeFilter
import com.android.dialer.databinding.DialogFilterContactSourcesBinding
import com.android.dialer.extensions.config
import com.android.dialer.models.RecentCall
import android.provider.CallLog.Calls
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class FilterCallTypesDialog(
    val activity: SimpleActivity,
    private val blurTarget: BlurTarget,
    private val recentCalls: List<RecentCall>,
    private val callback: () -> Unit
) {
    private val binding by activity.viewBinding(DialogFilterContactSourcesBinding::inflate)

    private var dialog: AlertDialog? = null

    init {
        activity.runOnUiThread {
            setupDialog()
        }
    }

    private fun setupDialog() {
        val callTypes = listOf(
            CallTypeFilter(null, activity.getString(R.string.all_g)),
            CallTypeFilter(Calls.INCOMING_TYPE, activity.getString(R.string.incoming_call)),
            CallTypeFilter(Calls.OUTGOING_TYPE, activity.getString(R.string.outgoing_call)),
            CallTypeFilter(Calls.MISSED_TYPE, activity.getString(R.string.missed_call))
        )

        // Count calls for each type
        val callTypesWithCount = callTypes.map { callType ->
            val count = if (callType.type == null) {
                recentCalls.size
            } else {
                recentCalls.count { it.type == callType.type }
            }
            callType.copy(count = count)
        }

        val selectedCallType = activity.config.recentCallsFilterType
        binding.filterContactSourcesList.adapter = FilterCallTypesAdapter(
            activity,
            callTypesWithCount,
            selectedCallType
        )

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
            text = activity.resources.getString(R.string.filter)
        }

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
        val negativeButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
        val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)

        buttonsContainer?.beVisible()

        positiveButton?.apply {
            beVisible()
            text = activity.resources.getString(R.string.ok)
            setTextColor(primaryColor)
            setOnClickListener { confirmCallTypeFilter() }
        }

        negativeButton?.apply {
            beVisible()
            text = activity.resources.getString(R.string.cancel)
            setTextColor(primaryColor)
            setOnClickListener { dialog?.dismiss() }
        }

        activity.getAlertDialogBuilder()
            .apply {
                // Pass empty titleText to prevent setupDialogStuff from adding title outside BlurView
                activity.setupDialogStuff(binding.root, this, titleText = "") { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun confirmCallTypeFilter() {
        val adapter = binding.filterContactSourcesList.adapter as FilterCallTypesAdapter
        val selectedCallType = adapter.getSelectedCallType()

        if (activity.config.recentCallsFilterType != selectedCallType) {
            activity.config.recentCallsFilterType = selectedCallType
            callback()
        }
        dialog?.dismiss()
    }
}
