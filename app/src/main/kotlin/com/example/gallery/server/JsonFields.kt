package com.example.gallery.server

/** Reads top-level string fields from small JSON request bodies, without a JSON library. */
object JsonFields {
    private val cache = HashMap<String, Regex>()

    fun string(raw: String?, name: String): String? {
        if (raw.isNullOrBlank()) return null
        val re = synchronized(cache) {
            cache.getOrPut(name) {
                Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            }
        }
        val m = re.find(raw) ?: return null
        return unescape(m.groupValues[1])
    }

    /** A top-level `true`/`false` field, or null when it is missing. */
    fun bool(raw: String?, name: String): Boolean? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*(true|false)").find(raw) ?: return null
        return m.groupValues[1] == "true"
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000c')
                'u' -> {
                    val code = if (i + 6 <= s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 6
                        continue
                    }
                    sb.append('u')
                }
                else -> sb.append(n)
            }
            i += 2
        }
        return sb.toString()
    }
}
