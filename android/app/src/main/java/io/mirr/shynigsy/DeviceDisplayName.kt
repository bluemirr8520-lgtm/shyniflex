package io.mirr.shynigsy

/** Build a bounded, non-secret device label for server login and TV pairing. */
internal fun deviceDisplayName(manufacturer: String?, model: String?, television: Boolean): String {
    fun clean(value: String?): String = value.orEmpty()
        .filterNot { it.code in 0..31 || it.code in 127..159 }
        .replace(Regex("\\s+"), " ").trim()
        .takeUnless { it.equals("unknown", ignoreCase = true) }.orEmpty()
    val maker = clean(manufacturer)
    val device = clean(model)
    val hardware = when {
        device.isEmpty() -> maker
        maker.isEmpty() || device.startsWith(maker, ignoreCase = true) -> device
        else -> "$maker $device"
    }.ifBlank { if (television) "Android TV" else "Android" }
    val suffix = if (television) " (shyniflex TV)" else " (shyniflex)"
    var prefix = hardware.take(200 - suffix.length).trimEnd()
    if (prefix.lastOrNull()?.isHighSurrogate() == true) prefix = prefix.dropLast(1)
    return prefix + suffix
}
