package com.hyperlyrics.mm.common

import java.net.URLEncoder

enum class FeedbackTopic(val tag: String) {
    BUG("Bug Report"),
    FEATURE("Feature Request"),
    GENERAL("Feedback")
}

data class FeedbackDeviceSnapshot(
    val appVersion: String,
    val versionCode: Int,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val romDisplay: String,
    val lsposedConnected: Boolean,
    val lsposedFramework: String,
    val lsposedApi: Int
)

object FeedbackMailFormatter {

    fun buildSubject(topic: FeedbackTopic, appVersion: String): String =
        "[HyperLyricsMM v$appVersion] ${topic.tag}"

    fun buildDiagnosticsText(
        device: FeedbackDeviceSnapshot,
        settings: ModuleSettings,
        recentLogs: List<String>
    ): String = buildString {
        appendLine("--- HyperLyricsMM Diagnostics ---")
        appendLine("App: v${device.appVersion} (${device.versionCode})")
        appendLine("Device: ${device.manufacturer} ${device.model}")
        appendLine("Android: ${device.androidRelease} (API ${device.sdkInt})")
        appendLine("ROM/Build: ${device.romDisplay}")
        val xposedSummary = if (device.lsposedConnected) {
            "${device.lsposedFramework.ifBlank { "LSPosed" }} (API ${device.lsposedApi})"
        } else {
            "Inactive / Not bound"
        }
        appendLine("LSPosed: $xposedSummary")
        appendLine(
            "Settings: master=${settings.masterEnabled}, expanded=${settings.expandedLyricsEnabled}, " +
                "autoPin=${settings.pinExpandedIslandEnabled}, pinBtn=${settings.showPinButtonOnIsland}, " +
                "minimal=${settings.minimalLyricsEnabled}, offlineSync=${settings.offlinePremiumSyncEnabled}, " +
                "fallback=${settings.externalFallbackEnabled}, offset=${settings.syncOffsetMs}ms"
        )
        if (recentLogs.isNotEmpty()) {
            appendLine("--- Recent Logs (last ${minOf(recentLogs.size, 15)}) ---")
            recentLogs.takeLast(15).forEach { line -> appendLine(line) }
        }
    }.trimEnd()

    fun buildEmailBody(
        userMessage: String,
        includeDiagnostics: Boolean,
        device: FeedbackDeviceSnapshot,
        settings: ModuleSettings,
        recentLogs: List<String>
    ): String {
        val trimmed = userMessage.trim()
        if (!includeDiagnostics) return trimmed
        val diag = buildDiagnosticsText(device, settings, recentLogs)
        return if (trimmed.isEmpty()) diag else "$trimmed\n\n$diag"
    }

    fun buildMailtoUriString(
        recipient: String = ModuleConfig.FEEDBACK_EMAIL,
        subject: String,
        body: String
    ): String {
        val encSubject = percentEncodeQueryComponent(subject)
        val encBody = percentEncodeQueryComponent(body)
        return "mailto:$recipient?subject=$encSubject&body=$encBody"
    }

    private fun percentEncodeQueryComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
