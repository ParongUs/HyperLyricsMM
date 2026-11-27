package com.hyperlyrics.mm.lyrics.ytm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class YouTubeProtobufLyricsScannerTest {

    @Test
    fun `extracts synced timed lyrics from YouTube offline protobuf blob`() {
        val cues = listOf(
            Triple("First offline timed line", 2_000L, 5_500L),
            Triple("Second offline timed line", 5_500L, 9_200L),
            Triple("Third offline timed line", 9_200L, 13_400L),
            Triple("Fourth offline timed line", 13_400L, 18_000L)
        )

        val outer = ByteArrayOutputStream()
        for ((text, startMs, endMs) in cues) {
            val cueRange = ByteArrayOutputStream().apply {
                writeVarintField(1, startMs)
                writeVarintField(2, endMs)
            }.toByteArray()
            val entry = ByteArrayOutputStream().apply {
                writeBytesField(1, text.toByteArray(Charsets.UTF_8))
                writeBytesField(2, cueRange)
            }.toByteArray()
            outer.writeBytesField(4, entry)
        }

        val parsed = YouTubeProtobufLyricsScanner.extractFromBlob(
            blob = outer.toByteArray(),
            videoId = "offLINE1234",
            title = "Offline Song",
            artist = "Premium Artist",
            durationMs = 180_000L
        )

        assertNotNull(parsed)
        parsed!!
        assertTrue(parsed.isSynced)
        assertEquals("YouTube Music Offline", parsed.sourceMessage)
        assertEquals(4, parsed.lines.size)
        assertEquals("First offline timed line", parsed.lines[0].text)
        assertEquals(2_000L, parsed.lines[0].startTimeMs)
        assertEquals(5_500L, parsed.lines[0].endTimeMs)
        assertEquals(13_400L, parsed.lines[3].startTimeMs)
    }

    @Test
    fun `extracts static multi-line offline lyrics block from protobuf blob`() {
        val block = listOf(
            "Line one from downloaded offline store",
            "Line two stored inside local entity",
            "Line three available in airplane mode",
            "Line four keeps playing smoothly",
            "Line five completes the static block"
        ).joinToString("\n")

        val blob = ByteArrayOutputStream().apply {
            writeBytesField(3, block.toByteArray(Charsets.UTF_8))
        }.toByteArray()

        val parsed = YouTubeProtobufLyricsScanner.extractFromBlob(
            blob = blob,
            videoId = "staTIC12345",
            title = "Offline Static",
            artist = "Artist"
        )

        assertNotNull(parsed)
        parsed!!
        assertFalse(parsed.isSynced)
        assertEquals(5, parsed.lines.size)
        assertEquals("Line three available in airplane mode", parsed.lines[2].text)
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                write(v.toInt())
                return
            }
            write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
    }

    private fun ByteArrayOutputStream.writeVarintField(fieldNum: Int, value: Long) {
        writeVarint(((fieldNum shl 3) or 0).toLong())
        writeVarint(value)
    }

    private fun ByteArrayOutputStream.writeBytesField(fieldNum: Int, payload: ByteArray) {
        writeVarint(((fieldNum shl 3) or 2).toLong())
        writeVarint(payload.size.toLong())
        write(payload)
    }
}
