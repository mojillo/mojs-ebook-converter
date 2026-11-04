package dev.mojillo.ebookconverter

import java.util.prefs.Preferences

enum class ThemeMode {
    System,
    Light,
    Dark,
}

object AppSettings {
    private val preferences = Preferences.userRoot().node("dev/mojillo/ebookconverter")

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(preferences.get("theme", ThemeMode.System.name)) }
            .getOrDefault(ThemeMode.System)
        set(value) {
            preferences.put("theme", value.name)
        }

    var removeDividers: Boolean
        get() = preferences.getBoolean("removeDividers", true)
        set(value) {
            preferences.putBoolean("removeDividers", value)
        }

    var outputFormat: OutputFormat
        get() = runCatching { OutputFormat.valueOf(preferences.get("format", OutputFormat.EPUB.name)) }
            .getOrDefault(OutputFormat.EPUB)
        set(value) {
            preferences.put("format", value.name)
        }
}
