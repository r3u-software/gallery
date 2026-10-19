package com.example.gallery.server

/** A photo folder ("album"): MediaStore's bucket name and how many photos it holds. */
data class Album(val name: String, val count: Int)

data class Contact(val id: Long, val name: String, val numbers: List<String>, val emails: List<String>)

interface ContactsProvider {
    fun hasPermission(): Boolean
    fun list(): List<Contact>
}

/** "Open on the phone": a link, the dialer or a map search. Shown on the phone, never auto-completed. */
interface OpenProvider {
    fun open(kind: String, value: String): Boolean
}

object OpenRequests {
    private val NUMBER = Regex("^[+0-9 ()\\-]{3,24}$")

    /** Returns an error message, or null when the request is acceptable. */
    fun validate(kind: String?, value: String?): String? {
        val v = value?.trim().orEmpty()
        return when (kind) {
            "link" -> when {
                v.isEmpty() || v.length > 2000 -> "Enter a link."
                v.any { it.isWhitespace() || it.code < 32 } -> "A link can't contain spaces."
                !(v.startsWith("http://", ignoreCase = true) || v.startsWith("https://", ignoreCase = true)) -> "Only http and https links can be opened."
                v.substringAfter("://").substringBefore('/').isEmpty() -> "That link has no address."
                else -> null
            }
            "dial" -> if (NUMBER.matches(v)) null else "Enter a valid phone number."
            "map" -> if (v.isEmpty() || v.length > 200 || v.any { it.code < 32 }) "Enter a place or address." else null
            else -> "Unknown action."
        }
    }
}

object JsonExtras {
    fun error(message: String): String = "{\"error\":" + Json.str(message) + "}"

    fun albums(total: Int, items: List<Album>): String =
        "{\"total\":$total,\"items\":[" + items.joinToString(",") {
            "{\"name\":${Json.str(it.name)},\"count\":${it.count}}"
        } + "]}"

    fun contacts(permission: Boolean, items: List<Contact>): String =
        "{\"permission\":$permission,\"items\":[" + items.joinToString(",") { c ->
            "{\"id\":${c.id},\"name\":${Json.str(c.name)}," +
                "\"numbers\":[" + c.numbers.joinToString(",") { Json.str(it) } + "]," +
                "\"emails\":[" + c.emails.joinToString(",") { Json.str(it) } + "]}"
        } + "]}"
}
