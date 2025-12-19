package com.android.dialer.dialogs

import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.viewBinding
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.adapters.RecentCallsAdapter
import com.android.dialer.databinding.DialogShowGroupedCallsBinding
import com.android.dialer.models.RecentCall
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import com.android.dialer.R

class ShowGroupedCallsDialog(val activity: BaseSimpleActivity, recentCalls: List<RecentCall>, blurTarget: BlurTarget) {
    private var dialog: AlertDialog? = null
    private val binding by activity.viewBinding(DialogShowGroupedCallsBinding::inflate)

    init {
        // Setup BlurView with the provided BlurTarget
        val blurView = binding.root.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(5f)
            ?.setBlurAutoUpdate(true)
        activity.runOnUiThread {
            RecentCallsAdapter(
                activity = activity as SimpleActivity,
                recyclerView = binding.selectGroupedCallsList,
                refreshItemsListener = null,
                showOverflowMenu = false,
                itemClick = {}
            ).apply {
                binding.selectGroupedCallsList.adapter = this
                updateItems(recentCalls)
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }
}
