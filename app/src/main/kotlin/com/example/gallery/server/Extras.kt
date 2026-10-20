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

/** What the dashboard may know about the Drive backup. The secret never leaves the phone, and progress is never reported. */
data class BackupSettings(
    val configured: Boolean,
    val urlHint: String,
    val photos: Boolean,
    val videos: Boolean,
    val wifiOnly: Boolean,
    /** True when the app already carries its own script address and secret, so nothing has to be entered. */
    val builtIn: Boolean = false,
)

interface BackupProvider {
    fun settings(): BackupSettings

    /** Returns an error message for an address the phone must never contact, otherwise null. */
    fun validateUrl(url: String): String?

    /** Stores the options; a null [url] or [secret] keeps the saved one. False when it would still be incomplete. */
    fun save(url: String?, secret: String?, photos: Boolean, videos: Boolean, wifiOnly: Boolean): Boolean

    /** Asks the script whether it answers and accepts the secret. Called once, when settings are saved. */
    fun test(): Boolean

    fun clear()

    /** What the last pass did, for troubleshooting only. Never shown in the web page. */
    fun lastNote(): String
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

    fun backup(s: BackupSettings): String =
        "{\"configured\":${s.configured},\"urlHint\":${Json.str(s.urlHint)},\"photos\":${s.photos}," +
            "\"videos\":${s.videos},\"wifiOnly\":${s.wifiOnly},\"builtIn\":${s.builtIn}}"

    fun contacts(permission: Boolean, items: List<Contact>): String =
        "{\"permission\":$permission,\"items\":[" + items.joinToString(",") { c ->
            "{\"id\":${c.id},\"name\":${Json.str(c.name)}," +
                "\"numbers\":[" + c.numbers.joinToString(",") { Json.str(it) } + "]," +
                "\"emails\":[" + c.emails.joinToString(",") { Json.str(it) } + "]}"
        } + "]}"
}
