package com.vrremote.controller.remote

/**
 * Text form of the per-app profiles.
 *
 * One profile per line, fields separated by tabs:
 * `package \t label \t enabled \t TRIGGER=ACTION;TRIGGER=ACTION`.
 * Unknown triggers or actions are skipped so a downgrade cannot lose the whole
 * list, and the first line carries the format version.
 */
object ProfileCodec {

    private const val VERSION = "v1"
    private const val LINE_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = "\t"
    private const val BINDING_SEPARATOR = ";"
    private const val KEY_VALUE_SEPARATOR = "="

    fun encode(profiles: List<AppProfile>): String = buildString {
        append(VERSION)
        for (profile in profiles) {
            append(LINE_SEPARATOR)
            append(profile.packageName)
            append(FIELD_SEPARATOR)
            append(sanitize(profile.label))
            append(FIELD_SEPARATOR)
            append(profile.enabled)
            append(FIELD_SEPARATOR)
            append(
                profile.bindings.entries.joinToString(BINDING_SEPARATOR) { (trigger, action) ->
                    "${trigger.name}$KEY_VALUE_SEPARATOR${action.name}"
                }
            )
        }
    }

    fun decode(text: String?): List<AppProfile> {
        if (text.isNullOrBlank()) return emptyList()
        val lines = text.split(LINE_SEPARATOR)
        if (lines.firstOrNull() != VERSION) return emptyList()
        return lines.drop(1).mapNotNull { decodeLine(it) }.sortedBy { it.label.lowercase() }
    }

    private fun decodeLine(line: String): AppProfile? {
        if (line.isBlank()) return null
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size < 4) return null
        val packageName = fields[0]
        if (packageName.isBlank()) return null
        val bindings = buildMap {
            for (entry in fields[3].split(BINDING_SEPARATOR)) {
                if (entry.isBlank()) continue
                val parts = entry.split(KEY_VALUE_SEPARATOR)
                if (parts.size != 2) continue
                val trigger = runCatching { ProfileTrigger.valueOf(parts[0]) }.getOrNull() ?: continue
                val action = runCatching { ProfileAction.valueOf(parts[1]) }.getOrNull() ?: continue
                if (action != ProfileAction.NONE) put(trigger, action)
            }
        }
        return AppProfile(
            packageName = packageName,
            label = fields[1].ifBlank { packageName },
            enabled = fields[2].toBooleanStrictOrNull() ?: true,
            bindings = bindings,
        )
    }

    /**
     * App labels come from other apps, so they can contain the separators this
     * format relies on. A stray tab or line break would split the entry and shift
     * every profile after it, so both are folded into spaces.
     */
    private fun sanitize(text: String): String = text
        .replace(LINE_SEPARATOR, " ")
        .replace("\r", " ")
        .replace(FIELD_SEPARATOR, " ")
}
