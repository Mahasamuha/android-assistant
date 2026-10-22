package com.mahasamuha.assistant.contacts

import android.content.ContentResolver
import android.content.res.Resources
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PhoneNumber(val number: String, val label: String)

data class Contact(val name: String, val numbers: List<PhoneNumber>)

/** Name and number lookups against the phone's contacts. Needs READ_CONTACTS. */
class ContactDirectory(private val resolver: ContentResolver, private val resources: Resources) {

    /**
     * Contacts whose name matches [query] (the provider's name filter: word prefixes, and nicknames),
     * with their numbers. The same number listed twice (e.g. from two accounts) appears once.
     */
    suspend fun find(query: String, limit: Int = 5): List<Contact> = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query))
        val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.TYPE, Phone.LABEL)
        val byContact = linkedMapOf<Long, Pair<String, MutableMap<String, PhoneNumber>>>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: continue
                val number = cursor.getString(2) ?: continue
                val key = cursor.getString(3) ?: number.filter { it.isDigit() || it == '+' }
                val label = Phone.getTypeLabel(resources, cursor.getInt(4), cursor.getString(5)).toString()
                byContact.getOrPut(id) { name to linkedMapOf() }.second.putIfAbsent(key, PhoneNumber(number, label))
            }
        }
        byContact.values.take(limit).map { (name, numbers) -> Contact(name, numbers.values.toList()) }
    }

    /**
     * Every contact saved with [number], with that number's label for each. Usually one; several when
     * the same number is saved under more than one contact.
     */
    suspend fun namesFor(number: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.TYPE, PhoneLookup.LABEL)
        val names = linkedMapOf<String, String>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                names.putIfAbsent(name, Phone.getTypeLabel(resources, cursor.getInt(1), cursor.getString(2)).toString())
            }
        }
        names.toList()
    }
}
