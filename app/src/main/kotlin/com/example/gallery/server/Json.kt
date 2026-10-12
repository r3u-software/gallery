package com.example.gallery.server

/** Tiny JSON writer so the server layer has no org.json (Android-only) dependency. */
object Json {
    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun photo(p: Photo): String =
        "{\"id\":${p.id},\"name\":${str(p.name)},\"dateTaken\":${p.dateTaken},\"size\":${p.size}," +
            "\"width\":${p.width},\"height\":${p.height},\"bucket\":${str(p.bucket)},\"mime\":${str(p.mime)}}"

    fun page(page: PhotoPage): String {
        val sb = StringBuilder(page.items.size * 140 + 64)
        sb.append("{\"items\":[")
        page.items.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append(photo(p))
        }
        sb.append("],\"nextCursor\":")
        sb.append(page.nextCursor?.let { str(it) } ?: "null")
        sb.append(",\"total\":").append(page.total).append('}')
        return sb.toString()
    }

    /** Extracts the numeric ids from `{"ids":[1,2,3]}` or a bare `1,2,3` string. */
    fun parseIds(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        val open = raw.indexOf('[')
        val close = raw.indexOf(']', open + 1)
        val region = if (open >= 0 && close > open) raw.substring(open + 1, close) else raw
        return ID_REGEX.findAll(region).mapNotNull { it.value.toLongOrNull() }.distinct().toList()
    }

    // ---------------------------------------------------------------- Phase 1 payloads

    fun status(d: DeviceStatus): String =
        "{\"name\":${str(d.name)},\"model\":${str(d.model)},\"androidVersion\":${str(d.androidVersion)}," +
            "\"batteryPercent\":${d.batteryPercent},\"charging\":${d.charging}," +
            "\"storageFreeBytes\":${d.storageFreeBytes},\"storageTotalBytes\":${d.storageTotalBytes}}"

    fun notifications(connected: Boolean, items: List<NotificationItem>): String =
        "{\"connected\":$connected,\"items\":[" + items.joinToString(",") {
            "{\"key\":${str(it.key)},\"app\":${str(it.app)},\"appName\":${str(it.appName)},\"title\":${str(it.title)}," +
                "\"text\":${str(it.text)},\"time\":${it.time},\"canReply\":${it.canReply}}"
        } + "]}"

    fun threads(permission: Boolean, items: List<SmsThread>): String =
        "{\"permission\":$permission,\"threads\":[" + items.joinToString(",") {
            "{\"id\":${it.id},\"address\":${str(it.address)},\"name\":${str(it.name)},\"snippet\":${str(it.snippet)}," +
                "\"date\":${it.date},\"unread\":${it.unread}}"
        } + "]}"

    fun messages(items: List<SmsMessage>): String =
        "{\"messages\":[" + items.joinToString(",") {
            "{\"id\":${it.id},\"body\":${str(it.body)},\"date\":${it.date},\"outgoing\":${it.outgoing}}"
        } + "]}"

    fun file(f: SharedFile): String =
        "{\"id\":${f.id},\"name\":${str(f.name)},\"size\":${f.size},\"modified\":${f.modified},\"mime\":${str(f.mime)}}"

    fun files(items: List<SharedFile>): String = "{\"items\":[" + items.joinToString(",") { file(it) } + "]}"

    fun clipboard(e: ClipboardEntry?): String =
        if (e == null) "{\"text\":null,\"time\":0}" else "{\"text\":${str(e.text)},\"time\":${e.time}}"

    fun session(passcodeRequired: Boolean, unlocked: Boolean, device: String, flags: Map<String, Boolean>): String =
        "{\"passcodeRequired\":$passcodeRequired,\"unlocked\":$unlocked,\"device\":${str(device)},\"features\":{" +
            flags.entries.joinToString(",") { "${str(it.key)}:${it.value}" } + "}}"

    private val ID_REGEX = Regex("\\d{1,18}")
}
