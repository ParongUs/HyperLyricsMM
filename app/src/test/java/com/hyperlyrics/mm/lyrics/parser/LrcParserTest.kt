package com.hyperlyrics.mm.lyrics.parser

import com.hyperlyrics.mm.lyrics.fallback.YouTubeCaptionsClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses synced LRC with multi-timestamps and global offset`() {
        val sampleLrc = """
            [ti:Sample Track]
            [ar:Test Artist]
            [offset:500]
            [00:05.00][00:25.50]Repeated chorus line
            [00:10.250]Second verse line
        """.trimIndent()

        val result = LrcParser.parseAuto(
            content = sampleLrc,
            title = "Sample Track",
            artist = "Test Artist",
            sourceMessage = "LRCLIB"
        )

        assertNotNull(result)
        result!!
        assertTrue(result.isSynced)
        assertEquals(3, result.lines.size)
        // 5000ms - 500ms offset = 4500ms
        assertEquals(4_500L, result.lines[0].startTimeMs)
        assertEquals("Repeated chorus line", result.lines[0].text)
        assertEquals(9_750L, result.lines[1].startTimeMs)
        assertEquals("Second verse line", result.lines[1].text)
        assertEquals(25_000L, result.lines[2].startTimeMs)
        assertEquals("Repeated chorus line", result.lines[2].text)
    }

    @Test
    fun `parses TTML timed paragraph lines`() {
        val sampleTtml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:00:03.200" end="00:00:06.800">Opening line &amp; intro</p>
                <p begin="00:00:07.000" end="00:00:11.500"><span>Next</span> timed line</p>
              </div></body>
            </tt>
        """.trimIndent()

        val result = LrcParser.parseAuto(
            content = sampleTtml,
            title = "TTML Song",
            artist = "Tester",
            sourceMessage = "Local File"
        )

        assertNotNull(result)
        result!!
        assertTrue(result.isSynced)
        assertEquals(2, result.lines.size)
        assertEquals(3_200L, result.lines[0].startTimeMs)
        assertEquals(6_800L, result.lines[0].endTimeMs)
        assertEquals("Opening line & intro", result.lines[0].text)
        assertEquals("Next timed line", result.lines[1].text)
    }

    @Test
    fun `parses plain unsynced lyrics cleanly`() {
        val plain = "First plain line\nSecond plain line\n\nThird plain line"
        val result = LrcParser.parseAuto(plain, "Plain", "Artist")
        assertNotNull(result)
        result!!
        assertFalse(result.isSynced)
        assertEquals(3, result.lines.size)
        assertEquals("Third plain line", result.lines[2].text)
    }

    @Test
    fun `parses YouTube json3 timed captions`() {
        val json3 = JSONObject(
            """
            {
              "events": [
                { "tStartMs": 1200, "dDurationMs": 2800, "segs": [{ "utf8": "First " }, { "utf8": "caption line" }] },
                { "tStartMs": 4500, "dDurationMs": 3100, "segs": [{ "utf8": "[Music]" }] },
                { "tStartMs": 8000, "dDurationMs": 3200, "segs": [{ "utf8": "Second spoken verse" }] },
                { "tStartMs": 12000, "dDurationMs": 2900, "segs": [{ "utf8": "Third timed phrase" }] }
              ]
            }
            """.trimIndent()
        )
        val parsed = YouTubeCaptionsClient.parseJson3Captions(
            json = json3,
            videoId = "abcDEF12345",
            title = "Live Acoustic",
            artist = "Indie Band",
            durationMs = 180_000L
        )
        assertNotNull(parsed)
        parsed!!
        assertTrue(parsed.isSynced)
        assertEquals("YouTube Captions", parsed.sourceMessage)
        assertEquals(3, parsed.lines.size)
        assertEquals("First caption line", parsed.lines[0].text)
        assertEquals(1200L, parsed.lines[0].startTimeMs)
    }
}
