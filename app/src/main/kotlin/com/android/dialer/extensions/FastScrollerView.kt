package com.android.dialer.extensions

import androidx.recyclerview.widget.RecyclerView
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.reddit.indicatorfastscroll.FastScrollerView
import com.goodwy.commons.models.contacts.Contact
import com.android.dialer.utils.CharUtils

fun FastScrollerView.setupWithContacts(
    recyclerView: RecyclerView,
    contacts: List<Contact>,
) = setupWithRecyclerView(recyclerView, { position ->
    val sectionIndicator = try {
        val contact = contacts[position]
        val name = contact.getBubbleText()
        
        // Check if the name starts with a Korean character
        if (name.isNotEmpty() && CharUtils.isKoreanCharacter(name[0])) {
            // Use Korean consonant grouping for Korean characters
            CharUtils.getFirstConsonant(name)
        } else {
            // Use the existing first letter method for non-Korean characters
            contact.getFirstLetter()
        }
    } catch (_: IndexOutOfBoundsException) {
        ""
    }

    FastScrollItemIndicator.Text(sectionIndicator)
})
