package com.android.dialer.extensions

import android.content.SharedPreferences
import android.telecom.PhoneAccountHandle
import com.android.dialer.helpers.sharedGson
import com.google.gson.JsonSyntaxException
import com.android.dialer.models.PhoneAccountHandleModel

fun SharedPreferences.Editor.putPhoneAccountHandle(
    key: String,
    parcelable: PhoneAccountHandle
): SharedPreferences.Editor {
    val componentName = parcelable.componentName
    val myPhoneAccountHandleModel = PhoneAccountHandleModel(
        componentName.packageName, componentName.className, parcelable.id
    )
    val json = sharedGson.toJson(myPhoneAccountHandleModel)
    return putString(key, json)
}

inline fun <reified T : PhoneAccountHandleModel?> SharedPreferences.getPhoneAccountHandleModel(
    key: String,
    default: T
): T {
    val json = getString(key, null)
    return try {
        if (json != null) {
            sharedGson.fromJson(json, T::class.java)
        } else {
            default
        }
    } catch (_: JsonSyntaxException) {
        default
    }
}
