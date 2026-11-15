package com.kandarp.chatarchive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** Phone number -> name, from the phone's own contacts (WhatsApp's backup has no names). */
object Contacts {
    fun normalize(raw: String, defaultCc: String = "91"): String? {
        val s = raw.trim()
        val intl = s.startsWith("+") || s.startsWith("00")
        var digits = s.filter { it.isDigit() }
        if (s.startsWith("00")) digits = digits.drop(2)
        if (digits.isEmpty()) return null
        if (!intl) {
            digits = digits.trimStart('0')
            if (digits.length <= 10) digits = defaultCc + digits
        }
        return digits.takeIf { it.length >= 7 }
    }

    fun load(ctx: Context, defaultCc: String = "91"): Map<String, String> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyMap()
        val out = HashMap<String, String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val num = c.getString(0) ?: continue
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                normalize(num, defaultCc)?.let { out.putIfAbsent(it, name) }
            }
        }
        return out
    }
}
