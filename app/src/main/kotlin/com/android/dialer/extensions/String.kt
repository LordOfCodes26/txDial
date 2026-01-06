package com.android.dialer.extensions

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.*

fun String.getCountryByNumber(): String {
    return try {
        val locale = Locale.getDefault()
        val countryCode = locale.country
        val phoneUtil = PhoneNumberUtil.getInstance()
        val geocoder = PhoneNumberOfflineGeocoder.getInstance()
        val numberParse = phoneUtil.parse(this, countryCode)
        geocoder.getDescriptionForNumber(numberParse, Locale.getDefault())
    } catch (_: NumberParseException) {
        ""
    }
}

// Phone number format and location functions are now in commons library
// Use: com.goodwy.commons.extensions.*
// Available functions:
// - formatPhoneNumberWithDistrict(context)
// - formatPhoneNumberWithDistrictAsync(context, callback)
// - getLocationByPrefix(context)
// - getLocationByPrefixAsync(context, callback)

// remove the pluses, spaces and hyphens.
fun String.numberForNotes() = replace("\\s".toRegex(), "")
    .replace("\\+".toRegex(), "")
    .replace("\\(".toRegex(), "")
    .replace("\\)".toRegex(), "")
    .replace("-".toRegex(), "")

fun String.removeNumberFormatting() = replace("\\s".toRegex(), "")
    .replace("\\(".toRegex(), "")
    .replace("\\)".toRegex(), "")
    .replace("-".toRegex(), "")
    .replace("\\*".toRegex(), "")
    .replace("#".toRegex(), "")
