package com.miit.app.band

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-app diagnostic log for Miit hardware testing. Never log authentication keys. */
object MiitTestLog {
    private val lock = Any()
    private val lines = mutableListOf<String>()
    private const val MAX_LINES = 2000

    fun add(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lock) {
            lines.add("[$stamp] $message")
            if (lines.size > MAX_LINES) lines.removeAt(0)
        }
    }

    fun clear() = synchronized(lock) { lines.clear() }

    fun text(context: Context): String = synchronized(lock) {
        buildString {
            appendLine("Miit hardware diagnostic log")
            appendLine("App: Miit Step 1 diagnostic")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Locale: ${context.resources.configuration.locales[0]}")
            appendLine("--- events ---")
            lines.forEach(::appendLine)
        }
    }
}
