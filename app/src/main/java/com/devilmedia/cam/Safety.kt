package com.devilmedia.cam

/** A single result on the safety check screen. */
data class Finding(val level: Level, val title: String, val detail: String) {
    enum class Level { GOOD, WARN, BAD }
}

/** Plain-language safety checks for a recorder. Nothing here tries to break in; it only looks at what we already know. */
object Safety {

    private val common = setOf(
        "12345", "123456", "1234567", "12345678", "123456789", "1234567890", "admin", "admin123", "admin1234",
        "password", "password1", "password123", "hikvision", "hik12345", "hik123456", "abc12345", "abcd1234",
        "qwerty", "qwerty123", "11111111", "00000000", "88888888", "666666", "888888", "letmein", "welcome",
        "a1234567", "1234abcd", "12345abc", "123456789a", "admin12345", "12345678a", "1q2w3e4r"
    )

    /** How strong the recorder password looks. It is checked here on the device and never sent anywhere. */
    fun password(user: String, pw: String): Finding {
        val lower = pw.lowercase()
        val kinds = listOf(pw.any { it.isLowerCase() }, pw.any { it.isUpperCase() }, pw.any { it.isDigit() },
            pw.any { !it.isLetterOrDigit() }).count { it }
        return when {
            lower in common || lower == user.lowercase() || lower.startsWith("hik") && lower.length <= 10 ->
                Finding(Finding.Level.BAD, "Password is too easy to guess",
                    "This is one of the passwords hackers try first. Change it on the recorder (Configuration → User Management), then update it here.")
            pw.length < 10 || kinds < 3 ->
                Finding(Finding.Level.WARN, "Password could be stronger",
                    "Use at least 10 characters with a mix of capitals, small letters, numbers and a symbol.")
            else -> Finding(Finding.Level.GOOD, "Strong password", "Your recorder password looks strong.")
        }
    }

    fun user(user: String): Finding? =
        if (user.equals("admin", ignoreCase = true))
            Finding(Finding.Level.WARN, "Using the main \"admin\" account",
                "That's fine for setting up. For day-to-day viewing, you can make a separate \"viewer\" user on the recorder that can only watch, and log in with that here.")
        else null

    fun upnp(enabled: Boolean?): Finding = when (enabled) {
        true -> Finding(Finding.Level.BAD, "UPnP is switched on",
            "UPnP lets the recorder open doors in your router to the internet by itself. Switch it off on the recorder: Configuration → Network → Advanced → UPnP. Hik-Connect keeps working without it.")
        false -> Finding(Finding.Level.GOOD, "UPnP is off", "The recorder isn't opening doors in your router by itself.")
        null -> Finding(Finding.Level.WARN, "Couldn't check UPnP",
            "The recorder didn't say whether UPnP is on. Check Configuration → Network → Advanced → UPnP and switch it off.")
    }

    fun firmware(model: String, firmware: String): Finding =
        Finding(Finding.Level.WARN, "Keep the recorder's software up to date",
            "Your ${model.ifBlank { "recorder" }} is running ${firmware.ifBlank { "unknown software" }}. Older Hikvision software has had serious security holes. Check for updates in Hik-Connect or on Hikvision's website.")

    fun away(): Finding =
        Finding(Finding.Level.GOOD, "No ports opened for Devil Cam",
            "Devil Cam watches your cameras over your home Wi-Fi. It never needs \"port forwarding\" on your router.")
}
