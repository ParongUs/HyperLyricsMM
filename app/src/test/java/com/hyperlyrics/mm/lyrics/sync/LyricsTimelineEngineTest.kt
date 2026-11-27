package com.hyperlyrics.mm.lyrics.sync

import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.lyrics.model.LyricLine
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimelineEngineTest {

    private val sampleSynced = TrackLyrics(
        videoId = "J7p4bzqLvCw",
        title = "Blinding Lights",
        artist = "The Weeknd",
        durationMs = 200_000L,
        isSynced = true,
        lines = listOf(
            LyricLine("First line", 5_000L, 8_000L),
            LyricLine("Second line", 8_000L, 12_000L),
            LyricLine("After instrumental", 20_000L, 24_000L)
        )
    )

    @Test
    fun `detects lead-in interlude before first vocal line`() {
        val snap = LyricsTimelineEngine.resolve(sampleSynced, rawPositionMs = 2_500L)
        assertTrue(snap.isInterlude)
        assertEquals(0.5f, snap.interludeProgress, 0.01f)
        assertEquals("First line", snap.nextText)
    }

    @Test
    fun `binary searches active line and respects sync offset`() {
        val snap1 = LyricsTimelineEngine.resolve(sampleSynced, rawPositionMs = 8_200L, offsetMs = 0)
        assertEquals(1, snap1.activeIndex)
        assertEquals("Second line", snap1.currentText)
        assertFalse(snap1.isInterlude)

        // With -500ms offset, 8200ms resolves to 7700ms -> still on First line
        val snapShifted = LyricsTimelineEngine.resolve(sampleSynced, rawPositionMs = 8_200L, offsetMs = -500)
        assertEquals(0, snapShifted.activeIndex)
        assertEquals("First line", snapShifted.currentText)
    }

    @Test
    fun `detects mid-song instrumental gap`() {
        val snap = LyricsTimelineEngine.resolve(sampleSynced, rawPositionMs = 16_000L)
        assertTrue(snap.isInterlude)
        assertEquals(0.5f, snap.interludeProgress, 0.01f)
        assertEquals("After instrumental", snap.nextText)
    }

    @Test
    fun `unsynced static lyrics advance proportionally`() {
        val staticTrack = TrackLyrics(
            title = "Static",
            artist = "Tester",
            durationMs = 100_000L,
            isSynced = false,
            lines = (0 until 10).map { LyricLine("Line $it", it * 1000L, (it + 1) * 1000L) }
        )
        val mid = LyricsTimelineEngine.resolve(staticTrack, rawPositionMs = 50_000L, durationMs = 100_000L)
        assertEquals(5, mid.activeIndex)
        assertEquals("Line 5", mid.currentText)
    }

    @Test
    fun `youtube music package matching accepts stock and revanced while ignoring others`() {
        assertTrue(ModuleConfig.isYouTubeMusicPackage("com.google.android.apps.youtube.music"))
        assertTrue(ModuleConfig.isYouTubeMusicPackage("app.rvx.android.apps.youtube.music"))
        assertTrue(ModuleConfig.isYouTubeMusicPackage("app.revanced.android.apps.youtube.music"))
        assertFalse(ModuleConfig.isYouTubeMusicPackage("com.spotify.music"))
        assertFalse(ModuleConfig.isYouTubeMusicPackage("com.miui.player"))
        assertFalse(ModuleConfig.isYouTubeMusicPackage(null))
    }
}
