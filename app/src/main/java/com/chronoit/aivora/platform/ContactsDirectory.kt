package com.chronoit.aivora.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.chronoit.aivora.brain.*

class ContactsDirectory(private val context: Context) : ContactDirectory {
    override suspend fun search(name: String): List<ContactCandidate> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Contacts access is missing")
        require(name.isNotBlank() && name.length <= 100)
        val columns = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL)
        fun query(selection: String, argument: String): List<ContactCandidate> {
            val result = mutableListOf<ContactCandidate>()
            context.contentResolver.query(Phone.CONTENT_URI, columns, selection, arrayOf(argument), "${Phone.DISPLAY_NAME} COLLATE LOCALIZED")?.use { cursor ->
                while (cursor.moveToNext() && result.size < 101) {
                    val number = normalizePhone(cursor.getString(2).orEmpty())
                    result += ContactCandidate(cursor.getLong(0).toString(), cursor.getString(1).orEmpty().take(100), number,
                        Phone.getTypeLabel(context.resources, cursor.getInt(3), cursor.getString(4)).toString())
                }
            }
            return result
        }
        val exact = query("${Phone.DISPLAY_NAME} = ? COLLATE NOCASE", name.trim())
        if (exact.isNotEmpty()) return exact
        val escaped = name.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return query("${Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'", "%$escaped%")
    }
    fun stillMatches(id: String, number: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        context.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER), "${Phone.CONTACT_ID} = ?", arrayOf(id), null)?.use { cursor ->
            while (cursor.moveToNext()) if (normalizePhone(cursor.getString(0).orEmpty()) == normalizePhone(number)) return true
        }
        return false
    }
}
