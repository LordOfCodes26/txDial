package com.android.dialer.extensions

import android.content.Context
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.helpers.ContactsHelper
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
 * Performance optimized: Loads secure box IDs in background and uses HashSet for O(1) lookup
 */
fun ContactsHelper.getContactsWithSecureBoxFilter(
    getAll: Boolean = false,
    gettingDuplicates: Boolean = false,
    ignoredContactSources: HashSet<String> = HashSet(),
    showOnlyContactsWithNumbers: Boolean? = null,
    callback: (ArrayList<Contact>) -> Unit
) {
    val helperContext = this.context
    val showOnlyNumbers = showOnlyContactsWithNumbers ?: helperContext.baseConfig.showOnlyContactsWithNumbers
    
    // Pre-load secure box IDs in background for better performance
    val secureBoxHelper = SecureBoxHelper(helperContext)
    val secureBoxContactIds = secureBoxHelper.getSecureBoxContactIds() // Uses cached IDs if available
    
    getContacts(getAll, gettingDuplicates, ignoredContactSources, showOnlyNumbers) { contacts ->
        // Filter out secure box contacts at data layer using HashSet for O(1) lookup
        // This ensures secure contacts never reach adapters, search, or any UI
        // For large contact lists, this is much faster than checking each contact individually
        val filteredContacts = if (secureBoxContactIds.isEmpty()) {
            // No secure contacts, return as-is (no filtering needed)
            contacts
        } else {
            // Use filterNot for better performance with large lists
            contacts.filterNot { it.id in secureBoxContactIds } as ArrayList<Contact>
        }
        
        callback(filteredContacts)
    }
}

