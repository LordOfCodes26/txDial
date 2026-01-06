package com.android.dialer.models

import android.telephony.PhoneNumberUtils
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.android.dialer.extensions.getDayCode
import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * Used at displaying recent calls.
 * For contacts with multiple numbers specify the number and type
 */
@kotlinx.serialization.Serializable
data class RecentCall(
    var id: Int,
    var phoneNumber: String,
    var name: String,
    var nickname: String = "",
    var company: String = "",
    var jobPosition: String = "",
    var photoUri: String,
    val startTS: Long,
    var duration: Int,
    var type: Int,
    val simID: Int,
    val simColor: Int,
    var specificNumber: String,
    var specificType: String,
    val isUnknownNumber: Boolean,
    @SerializedName("title") val groupedCalls: MutableList<RecentCall>? = null,
    var contactID: Int? = null,
    var features: Int? = null,
    val isVoiceMail: Boolean,
    var blockReason: Int? = 0,
) : CallLogItem(), Serializable {
    val dayCode = startTS.getDayCode()

    fun doesContainPhoneNumber(text: String): Boolean {
        if (text.isEmpty()) return false
        
        // Check if text contains any digits (for phone number search)
        val hasDigits = text.any { it.isDigit() }
        if (!hasDigits) return false
        
        // Extract digits and + sign for phone number matching
        val numericText = text.filter { it.isDigit() || it == '+' }
        if (numericText.isEmpty()) return false
        
        val normalizedText = numericText.normalizePhoneNumber()
        val normalizedPhoneNumber = phoneNumber.normalizePhoneNumber()
        
        return PhoneNumberUtils.compare(normalizedPhoneNumber, normalizedText) ||
            phoneNumber.contains(text) ||
            normalizedPhoneNumber.contains(normalizedText) ||
            phoneNumber.contains(normalizedText) ||
            normalizedPhoneNumber.contains(numericText) ||
            phoneNumber.contains(numericText)
    }

    fun isABusinessCall() = name.contains(company) && company.isNotEmpty()
}
