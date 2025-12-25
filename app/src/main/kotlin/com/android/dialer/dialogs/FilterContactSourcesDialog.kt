package com.android.dialer.dialogs

import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.ContactsHelper
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.models.contacts.ContactSource
import com.android.dialer.R
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.adapters.FilterContactSourcesAdapter
import com.android.dialer.databinding.DialogFilterContactSourcesBinding
import com.android.dialer.extensions.config
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class FilterContactSourcesDialog(val activity: SimpleActivity, private val blurTarget: BlurTarget, private val callback: () -> Unit) {
    private val binding by activity.viewBinding(DialogFilterContactSourcesBinding::inflate)

    private var dialog: AlertDialog? = null
    private var contactSources = mutableListOf<ContactSource>()
    private var contacts = mutableListOf<Contact>()
    private var isContactSourcesReady = false
    private var isContactsReady = false

    init {
        val contactHelper = ContactsHelper(activity)
        contactHelper.clearContactSourcesCache()
        contactHelper.getContactSources { contactSources ->
            contactSources.mapTo(this@FilterContactSourcesDialog.contactSources) { it.copy() }
            isContactSourcesReady = true
            processDataIfReady()
        }

        contactHelper.getContacts(getAll = true) {
            it.mapTo(contacts) { contact -> contact.copy() }
            isContactsReady = true
            processDataIfReady()
        }
    }

    private fun processDataIfReady() {
        if (!isContactSourcesReady) {
            return
        }

        val contactSourcesWithCount = ArrayList<ContactSource>()
        for (contactSource in contactSources) {
            val count = if (isContactsReady) {
                contacts.count { it.source == contactSource.name }
            } else {
                -1
            }
            contactSourcesWithCount.add(contactSource.copy(count = count))
        }

        contactSources.clear()
        contactSources.addAll(contactSourcesWithCount)

        activity.runOnUiThread {
            val selectedSources = activity.getVisibleContactSources()
            binding.filterContactSourcesList.adapter = FilterContactSourcesAdapter(activity, contactSourcesWithCount, selectedSources)

            if (dialog == null) {
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
                    setOnClickListener { confirmContactSources() }
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
        }
    }

    private fun confirmContactSources() {
        val selectedContactSources = (binding.filterContactSourcesList.adapter as FilterContactSourcesAdapter).getSelectedContactSources()
        val ignoredContactSources = contactSources.filter { !selectedContactSources.contains(it) }.map {
            it.getFullIdentifier()
        }.toHashSet()

        if (activity.getVisibleContactSources() != ignoredContactSources) {
            activity.config.ignoredContactSources = ignoredContactSources
            val contactHelper = ContactsHelper(activity)
            contactHelper.clearContactSourcesCache()
            callback()
        }
        dialog?.dismiss()
    }
}
