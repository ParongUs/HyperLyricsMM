package com.hyperlyrics.mm.lyrics.ytm

import com.hyperlyrics.mm.lyrics.model.LyricLine
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Pure, bounds-checked wire-format Protobuf scanner designed to recover offline lyrics
 * directly from YouTube Music Premium's local SQLite entity/offline protobuf blobs
 * (raw or GZIP-compressed) when the device has no internet connection.
 *
 * Extracts:
 *  1. Synced timed lyric entries: submessages holding a valid UTF-8 lyric line alongside
 *     a CueRange submessage with millisecond varints (supports YouTube extension fields
 *     and proto3 omitted-zero start timestamps).
 *  2. Static multi-line lyric payloads: UTF-8 lyric text blocks inside offline entity blobs.
 */
object YouTubeProtobufLyricsScanner {

    private const val MAX_DEPTH = 14
    private const val MAX_TRACK_DURATION_MS = 3_600_000L // 60 minutes sanity cap
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    fun extractFromBlob(
        blob: ByteArray,
        videoId: String?,
        title: String,
        artist: String,
        durationMs: Long = 0L
    ): TrackLyrics? {
        if (blob.size < 16 || blob.size > 2 * 1024 * 1024) return null

        val data = maybeGunzip(blob) ?: blob
        if (data.size < 16 || data.size > 4 * 1024 * 1024) return null

        val timedCues = mutableListOf<LyricLine>()
        val staticBlocks = mutableListOf<String>()

        scanMessage(
            data = data,
            start = 0,
            end = data.size,
            depth = 0,
            timedOut = timedCues,
            staticOut = staticBlocks
        )

        // 1. Prefer valid monotonically-ordered synced cues (at least 4 lines)
        val cleanedTimed = deduplicateAndValidateTimed(timedCues)
        if (cleanedTimed.size >= 4) {
            return TrackLyrics(
                videoId = videoId,
                title = title,
                artist = artist,
                durationMs = durationMs,
                lines = cleanedTimed,
                isSynced = true,
                sourceMessage = "YouTube Music Offline"
            )
        }

        // 2. Fall back to multi-line static lyrics block stored inside offline blob
        val bestStatic = staticBlocks.maxByOrNull { it.length }
        if (bestStatic != null) {
            val rawLines = bestStatic.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (rawLines.size >= 4) {
                val lines = rawLines.mapIndexed { idx, line ->
                    LyricLine(
                        text = line,
                        startTimeMs = idx * 1000L,
                        endTimeMs = (idx + 1) * 1000L,
                        isInterlude = isInterlude(line)
                    )
                }
                return TrackLyrics(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    lines = lines,
                    isSynced = false,
                    sourceMessage = "YouTube Music Offline"
                )
            }
        }

        return null
    }

    private fun maybeGunzip(blob: ByteArray): ByteArray? {
        if (blob.size < 18) return null
        if (blob[0] != 0x1F.toByte() || blob[1] != 0x8B.toByte()) return null
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(blob)).use { it.readBytes() }
        }.getOrNull()
    }

    private fun scanMessage(
        data: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        timedOut: MutableList<LyricLine>,
        staticOut: MutableList<String>
    ) {
        if (depth > MAX_DEPTH || start >= end) return

        // First check if THIS message itself is a TimedLyricLine (string + cueRange submessage)
        tryParseAsTimedCue(data, start, end)?.let { cue ->
            timedOut.add(cue)
            return
        }

        var pos = start
        while (pos < end) {
            val tagRes = readVarint(data, pos, end) ?: return
            pos = tagRes.nextPos
            val wireType = (tagRes.value and 0x7L).toInt()
            val fieldNum = (tagRes.value ushr 3).toInt()
            if (fieldNum <= 0 || fieldNum > 536_870_911) return

            when (wireType) {
                WIRE_VARINT -> {
                    val v = readVarint(data, pos, end) ?: return
                    pos = v.nextPos
                }

                WIRE_FIXED64 -> {
                    if (pos + 8 > end) return
                    pos += 8
                }

                WIRE_FIXED32 -> {
                    if (pos + 4 > end) return
                    pos += 4
                }

                WIRE_LEN -> {
                    val lenRes = readVarint(data, pos, end) ?: return
                    pos = lenRes.nextPos
                    val len = lenRes.value.toInt()
                    if (len < 0 || pos + len > end) return
                    val subStart = pos
                    val subEnd = pos + len
                    pos = subEnd

                    if (len >= 60) {
                        tryDecodeCleanUtf8(data, subStart, subEnd)?.let { str ->
                            if (looksLikeStaticLyricsBlock(str)) {
                                staticOut.add(str)
                            }
                        }
                    }

                    if (len >= 6) {
                        scanMessage(data, subStart, subEnd, depth + 1, timedOut, staticOut)
                    }
                }

                else -> return
            }
        }
    }

    /**
     * Checks if a protobuf message represents a YouTube TimedLyrics entry:
     * contains 1 readable single-line lyric text and a nested CueRange submessage
     * (or direct varint fields) with valid startMs <= endMs millisecond timestamps.
     * Supports YouTube protobuf extension field numbers (>100) and logging submessages.
     */
    private fun tryParseAsTimedCue(data: ByteArray, start: Int, end: Int): LyricLine? {
        val len = end - start
        if (len < 6 || len > 2048) return null

        var textCandidate: String? = null
        var startMs: Long = -1L
        var endMs: Long = -1L
        var hasCueRangeSubmessage = false
        var pos = start

        while (pos < end) {
            val tagRes = readVarint(data, pos, end) ?: return null
            pos = tagRes.nextPos
            val wireType = (tagRes.value and 0x7L).toInt()
            val fieldNum = (tagRes.value ushr 3).toInt()
            if (fieldNum <= 0 || fieldNum > 536_870_911) return null

            when (wireType) {
                WIRE_VARINT -> {
                    val v = readVarint(data, pos, end) ?: return null
                    pos = v.nextPos
                    if (!hasCueRangeSubmessage && v.value in 0L..MAX_TRACK_DURATION_MS) {
                        if (startMs < 0L) startMs = v.value
                        else if (endMs < 0L && v.value >= startMs) endMs = v.value
                    }
                }

                WIRE_FIXED64 -> {
                    if (pos + 8 > end) return null
                    pos += 8
                }

                WIRE_FIXED32 -> {
                    if (pos + 4 > end) return null
                    pos += 4
                }

                WIRE_LEN -> {
                    val lenRes = readVarint(data, pos, end) ?: return null
                    pos = lenRes.nextPos
                    val subLen = lenRes.value.toInt()
                    if (subLen < 0 || pos + subLen > end) return null
                    val subStart = pos
                    val subEnd = pos + subLen
                    pos = subEnd

                    // Could be CueRange submessage holding startTimeMs & endTimeMs
                    val cueRange = parseCueRangeSubmessage(data, subStart, subEnd)
                    if (cueRange != null) {
                        startMs = cueRange.first
                        endMs = cueRange.second
                        hasCueRangeSubmessage = true
                    } else if (textCandidate == null && subLen in 1..240) {
                        val decoded = tryDecodeCleanUtf8(data, subStart, subEnd)?.trim()
                        if (decoded != null && looksLikeSingleLyricLine(decoded)) {
                            textCandidate = decoded
                        }
                    }
                }

                else -> return null
            }
        }

        val text = textCandidate ?: return null
        if (startMs < 0L || endMs < startMs) return null
        if (endMs == startMs && startMs == 0L) return null
        if (endMs - startMs > 120_000L) return null // single lyric line shouldn't span >2 minutes

        return LyricLine(
            text = if (isInterlude(text)) "♪" else text,
            startTimeMs = startMs,
            endTimeMs = endMs,
            isInterlude = isInterlude(text)
        )
    }

    private fun parseCueRangeSubmessage(data: ByteArray, start: Int, end: Int): Pair<Long, Long>? {
        if (end - start !in 2..256) return null
        var pos = start
        var field1: Long? = null
        var field2: Long? = null
        val varints = ArrayList<Long>(4)

        while (pos < end) {
            val tagRes = readVarint(data, pos, end) ?: return null
            pos = tagRes.nextPos
            val wireType = (tagRes.value and 0x7L).toInt()
            val fieldNum = (tagRes.value ushr 3).toInt()
            if (fieldNum <= 0 || fieldNum > 536_870_911) return null

            when (wireType) {
                WIRE_VARINT -> {
                    val v = readVarint(data, pos, end) ?: return null
                    pos = v.nextPos
                    if (fieldNum == 1) field1 = v.value
                    else if (fieldNum == 2) field2 = v.value
                    if (v.value in 0L..MAX_TRACK_DURATION_MS) {
                        varints.add(v.value)
                    }
                }

                WIRE_FIXED64 -> {
                    if (pos + 8 > end) return null
                    pos += 8
                }

                WIRE_FIXED32 -> {
                    if (pos + 4 > end) return null
                    pos += 4
                }

                WIRE_LEN -> {
                    val lenRes = readVarint(data, pos, end) ?: return null
                    pos = lenRes.nextPos
                    val subLen = lenRes.value.toInt()
                    if (subLen < 0 || pos + subLen > end) return null
                    // Handle string-encoded millisecond timestamps if present, otherwise skip metadata submessage
                    if (subLen in 1..10 && (fieldNum == 1 || fieldNum == 2)) {
                        val strNum = tryDecodeCleanUtf8(data, pos, pos + subLen)?.toLongOrNull()
                        if (strNum != null && strNum in 0L..MAX_TRACK_DURATION_MS) {
                            if (fieldNum == 1) field1 = strNum
                            else field2 = strNum
                            varints.add(strNum)
                        }
                    }
                    pos += subLen
                }

                else -> return null
            }
        }

        // Standard CueRange: field 1 = startTimeMs, field 2 = endTimeMs.
        // In proto3, startTimeMs == 0 is omitted on wire, leaving field 2 = endTimeMs only.
        val s: Long
        val e: Long
        when {
            field1 != null && field2 != null -> {
                s = field1
                e = field2
            }
            field1 == null && field2 != null && field2 in 200L..60_000L -> {
                s = 0L
                e = field2
            }
            varints.size >= 2 -> {
                s = varints[0]
                e = varints[1]
            }
            else -> return null
        }

        if (s !in 0L..MAX_TRACK_DURATION_MS || e !in s..MAX_TRACK_DURATION_MS) return null
        if (e - s < 150L || e - s > 90_000L) return null
        return s to e
    }

    private fun deduplicateAndValidateTimed(raw: List<LyricLine>): List<LyricLine> {
        if (raw.size < 4) return emptyList()
        val sorted = raw.sortedBy { it.startTimeMs }
        val result = ArrayList<LyricLine>(sorted.size)
        for (line in sorted) {
            val prev = result.lastOrNull()
            if (prev != null && prev.startTimeMs == line.startTimeMs && prev.text == line.text) {
                continue
            }
            result.add(line)
        }
        // Require realistic time progression (at least 10 seconds span across cues)
        val firstStart = result.firstOrNull()?.startTimeMs ?: return emptyList()
        val lastEnd = result.lastOrNull()?.endTimeMs ?: return emptyList()
        if (lastEnd - firstStart < 10_000L) return emptyList()
        return result
    }

    private fun looksLikeSingleLyricLine(text: String): Boolean {
        if (text.isEmpty() || text.length > 200) return false
        if (text.contains('\n') || text.contains('\r')) return false
        if (text.startsWith("http://") || text.startsWith("https://")) return false
        if (text.startsWith("MPLY") || text.startsWith("{") || text.startsWith("<")) return false
        if (text.contains("://") || text.contains("youtubei/")) return false
        if (isInterlude(text)) return true
        val letters = text.count { it.isLetter() }
        return letters >= 2
    }

    private fun looksLikeStaticLyricsBlock(text: String): Boolean {
        if (text.length !in 80..25_000) return false
        if (text.contains("http://") || text.contains("https://") || text.contains("youtubei/")) return false
        if (text.contains("{") && text.contains("}") && text.contains("\":")) return false
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 4) return false
        val avgLen = lines.map { it.length }.average()
        return avgLen in 8.0..95.0
    }

    private fun tryDecodeCleanUtf8(data: ByteArray, start: Int, end: Int): String? {
        for (i in start until end) {
            val b = data[i].toInt() and 0xFF
            if (b < 0x20 && b != 0x0A && b != 0x0D && b != 0x09) return null
            if (b == 0x7F) return null
        }
        val decoded = String(data, start, end - start, StandardCharsets.UTF_8)
        if (decoded.contains('\uFFFD')) return null
        return decoded
    }

    private fun isInterlude(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && trimmed.all { it == '♪' || it == '♫' || it == '♬' || it.isWhitespace() }
    }

    private data class VarintResult(val value: Long, val nextPos: Int)

    private fun readVarint(data: ByteArray, start: Int, end: Int): VarintResult? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < end && shift < 64) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) {
                return VarintResult(result, pos)
            }
            shift += 7
        }
        return null
    }
}
