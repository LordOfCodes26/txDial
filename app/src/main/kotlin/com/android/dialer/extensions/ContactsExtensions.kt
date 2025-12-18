package com.android.dialer.extensions

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.helpers.ContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.securebox.SecureBoxHelper
import java.util.HashSet

/**
 * Extension functions for filtering secure box contacts
 */
fun List<Contact>.filterSecureBox(context: Context): List<Contact> {
    val secureBoxHelper = SecureBoxHelper(context)
    val secureBoxContactIds = secureBoxHelper.getSecureBoxContactIds()
    return this.filter { it.id !in secureBoxContactIds }
}

fun ArrayList<Contact>.filterSecureBox(context: Context): ArrayList<Contact> {
    val secureBoxHelper = SecureBoxHelper(context)
    val secureBoxContactIds = secureBoxHelper.getSecureBoxContactIds()
    return ArrayList(this.filter { it.id !in secureBoxContactIds })
}

/**
 * Wrapper for ContactsHelper.getContacts that automatically filters out secure box contacts
 * This ensures secure contacts never reach adapters, search, or any UI unless unlocked
 *
 * Performance optimized: Loads secure box IDs off the main thread and uses HashSet-style lookup
 */
fun ContactsHelper.getContactsWithSecureBoxFilter(
    getAll: Boolean = false,
    gettingDuplicates: Boolean = false,
    ignoredContactSources: HashSet<String> = HashSet(),
    showOnlyContactsWithNumbers: Boolean? = null,
    loadExtendedFields: Boolean = true, // Set to false for faster list loading (addresses, events, notes, websites, relations, IMs)
    callback: (ArrayList<Contact>) -> Unit
) {
    val helperContext = this.context
    val showOnlyNumbers = showOnlyContactsWithNumbers ?: helperContext.baseConfig.showOnlyContactsWithNumbers
    val mainHandler = Handler(Looper.getMainLooper())

    getContacts(getAll, gettingDuplicates, ignoredContactSources, showOnlyNumbers, loadExtendedFields) { contacts ->
        // Move secure box DB access and filtering off the main thread to avoid Room's
        // "Cannot access database on the main thread" IllegalStateException.
        ensureBackgroundThread {
            val secureBoxHelper = SecureBoxHelper(helperContext)
            val secureBoxContactIds = secureBoxHelper.getSecureBoxContactIds()

            val filteredContacts = if (secureBoxContactIds.isEmpty()) {
                contacts
            } else {
                ArrayList(contacts.filterNot { it.id in secureBoxContactIds })
            }

            mainHandler.post {
                callback(filteredContacts)
            }
        }
    }
}

