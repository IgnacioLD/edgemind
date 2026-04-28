package com.vela.assistant.data.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsTools @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolSet {

    @Tool(description = "Looks up contacts by name and returns their phone numbers and emails. Returns up to 5 matches.")
    fun lookupContact(
        @ToolParam(description = "Contact name as the user said it (partial matches work)") name: String,
    ): String {
        Timber.i("TOOL lookupContact(name='$name')")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Contacts permission is not granted. Tell the user to grant Contacts permission in app settings."
        }
        if (name.isBlank()) return "Error: name is empty."

        val results = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT 5",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getString(idIdx) ?: continue
                val displayName = c.getString(nameIdx) ?: continue
                val phones = phonesFor(id)
                val emails = emailsFor(id)
                val parts = buildList {
                    add(displayName)
                    if (phones.isNotEmpty()) add("phone ${phones.joinToString(", ")}")
                    if (emails.isNotEmpty()) add("email ${emails.joinToString(", ")}")
                }
                results += parts.joinToString(" — ")
            }
        }
        return if (results.isEmpty()) "No contacts found matching '$name'." else results.joinToString("\n")
    }

    private fun phonesFor(contactId: String): List<String> {
        val phones = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                c.getString(idx)?.let { phones += it }
            }
        }
        return phones.distinct()
    }

    private fun emailsFor(contactId: String): List<String> {
        val emails = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (c.moveToNext()) {
                c.getString(idx)?.let { emails += it }
            }
        }
        return emails.distinct()
    }
}
