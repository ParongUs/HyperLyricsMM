package com.hyperlyrics.mm.common

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object ModuleLogger {
    private const val ROOT_TAG = "HyperLyricsMM"
    private const val MAX_BUFFER = 120
    private val buffer = CopyOnWriteArrayList<String>()

    @Volatile
    var xposedLogSink: ((Int, String, Throwable?) -> Unit)? = null

    fun d(tag: String, message: String) {
        val full = "[$tag] $message"
        runCatching { Log.d(ROOT_TAG, full) }
        append("D", full)
    }

    fun i(tag: String, message: String) {
        val full = "[$tag] $message"
        runCatching { Log.i(ROOT_TAG, full) }
        runCatching { xposedLogSink?.invoke(Log.INFO, full, null) }
        append("I", full)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        val full = "[$tag] $message"
        runCatching {
            if (error != null) Log.w(ROOT_TAG, full, error) else Log.w(ROOT_TAG, full)
        }
        runCatching { xposedLogSink?.invoke(Log.WARN, full, error) }
        append("W", if (error != null) "$full (${error.javaClass.simpleName}: ${error.message})" else full)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        val full = "[$tag] $message"
        runCatching {
            if (error != null) Log.e(ROOT_TAG, full, error) else Log.e(ROOT_TAG, full)
        }
        runCatching { xposedLogSink?.invoke(Log.ERROR, full, error) }
        append("E", if (error != null) "$full (${error.javaClass.simpleName}: ${error.message})" else full)
    }

    fun recentLogs(): List<String> = buffer.toList()

    fun clearLogs() = buffer.clear()

    private fun append(level: String, message: String) {
        val ts = runCatching {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        }.getOrDefault("--:--:--")
        buffer.add("$ts $level/$message")
        while (buffer.size > MAX_BUFFER) {
            runCatching { buffer.removeAt(0) }
        }
    }
}
