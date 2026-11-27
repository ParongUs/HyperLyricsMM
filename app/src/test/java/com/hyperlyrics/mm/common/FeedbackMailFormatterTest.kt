package com.hyperlyrics.mm.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackMailFormatterTest {

    private val sampleDevice = FeedbackDeviceSnapshot(
        appVersion = "1.1.0",
        versionCode = 110,
        androidRelease = "15",
        sdkInt = 35,
        manufacturer = "Xiaomi",
        model = "24031PN0DC",
        romDisplay = "OS2.0.104.0.VNCMIXM",
        lsposedConnected = true,
        lsposedFramework = "LSPosed",
        lsposedApi = 101
    )

    @Test
    fun `builds expected subject and mailto uri targeting developer email`() {
        val subject = FeedbackMailFormatter.buildSubject(FeedbackTopic.BUG, "1.1.0")
        assertEquals("[HyperLyricsMM v1.1.0] Bug Report", subject)

        val uri = FeedbackMailFormatter.buildMailtoUriString(
            subject = subject,
            body = "Ada kilitliyken harika akıyor!"
        )
        assertTrue(uri.startsWith("mailto:mehmeteymensevik@gmail.com?subject="))
        assertFalse(uri.contains("+"))
        assertTrue(uri.contains("%20"))
    }

    @Test
    fun `includes diagnostics and truncates logs to last 15 lines when enabled`() {
        val logs = (1..25).map { "10:00:%02d I/[Test] line $it".format(it) }
        val body = FeedbackMailFormatter.buildEmailBody(
            userMessage = "Test feedback message",
            includeDiagnostics = true,
            device = sampleDevice,
            settings = ModuleSettings(pinExpandedIslandEnabled = true),
            recentLogs = logs
        )

        assertTrue(body.startsWith("Test feedback message"))
        assertTrue(body.contains("Xiaomi 24031PN0DC"))
        assertTrue(body.contains("autoPin=true"))
        assertTrue(body.contains("line 25"))
        assertFalse(body.contains("line 1\n"))
    }

    @Test
    fun `omits diagnostics cleanly when disabled`() {
        val body = FeedbackMailFormatter.buildEmailBody(
            userMessage = "   Sadece kısa mesaj   ",
            includeDiagnostics = false,
            device = sampleDevice,
            settings = ModuleSettings(),
            recentLogs = listOf("log line")
        )
        assertEquals("Sadece kısa mesaj", body)
    }
}
