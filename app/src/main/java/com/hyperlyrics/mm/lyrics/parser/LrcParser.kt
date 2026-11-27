package com.hyperlyrics.mm.lyrics.parser

import com.hyperlyrics.mm.lyrics.model.LyricLine
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import kotlin.math.roundToLong

/**
 * Production-grade parser for `.lrc` (synced LRC with multi-timestamps & [offset:]),
 * `.ttml` (`<p begin="..." end="...">`), and plain multi-line lyrics text.
 */
object LrcParser {

    private val LRC_TAG_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET_TAG_REGEX = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val META_TAG_REGEX = Regex("""^\[(ti|ar|al|au|by|re|ve|length|id|offset):.*]$""", RegexOption.IGNORE_CASE)
    private val INLINE_WORD_TAG_REGEX = Regex("""<\d{1,2}:\d{2}(?:[.:]\d{1,3})?>""")
    private val TTML_P_REGEX = Regex(
        """<p\b([^>]*)>(.*?)</p>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val TTML_BEGIN_ATTR_REGEX = Regex("""\bbegin\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val TTML_END_ATTR_REGEX = Regex("""\bend\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")

    fun parseAuto(
        content: String,
        title: String,
        artist: String,
        videoId: String? = null,
        durationMs: Long = 0L,
        sourceMessage: String? = null
    ): TrackLyrics? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        // 1. TTML XML format
        if (trimmed.contains("<tt", ignoreCase = true) || trimmed.contains("<p ", ignoreCase = true)) {
            parseTtml(trimmed, title, artist, videoId, durationMs, sourceMessage)?.let { return it }
        }

        // 2. Synced LRC format
        if (LRC_TAG_REGEX.containsMatchIn(trimmed)) {
            parseLrc(trimmed, title, artist, videoId, durationMs, sourceMessage)?.let { return it }
        }

        // 3. Plain static lyrics fallback
        val plainLines = trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !META_TAG_REGEX.matches(it) }
        if (plainLines.isEmpty()) return null

        return TrackLyrics(
            videoId = videoId,
            title = title,
            artist = artist,
            durationMs = durationMs,
            lines = plainLines.mapIndexed { idx, line ->
                LyricLine(
                    text = line,
                    startTimeMs = idx * 1000L,
                    endTimeMs = (idx + 1) * 1000L,
                    isInterlude = isInterlude(line)
                )
            },
            isSynced = false,
            sourceMessage = sourceMessage
        )
    }

    fun parseLrc(
        lrcText: String,
        title: String,
        artist: String,
        videoId: String? = null,
        durationMs: Long = 0L,
        sourceMessage: String? = null
    ): TrackLyrics? {
        val globalOffsetMs = OFFSET_TAG_REGEX.find(lrcText)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val entries = mutableListOf<Pair<Long, String>>()

        for (rawLine in lrcText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || META_TAG_REGEX.matches(line)) continue

            val matches = LRC_TAG_REGEX.findAll(line).toList()
            if (matches.isEmpty()) continue

            val lyricBody = LRC_TAG_REGEX.replace(line, "")
                .replace(INLINE_WORD_TAG_REGEX, "")
                .trim()

            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: continue
                val seconds = match.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = match.groupValues[3]
                val millis = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                val ts = (minutes * 60_000L + seconds * 1_000L + millis - globalOffsetMs).coerceAtLeast(0L)
                entries.add(ts to lyricBody)
            }
        }

        if (entries.isEmpty()) return null
        val sorted = entries.sortedBy { it.first }
        val lines = ArrayList<LyricLine>(sorted.size)

        for (i in sorted.indices) {
            val (startMs, rawText) = sorted[i]
            val nextStart = sorted.getOrNull(i + 1)?.first
            val endMs = when {
                nextStart != null && nextStart > startMs -> nextStart
                durationMs > startMs -> minOf(durationMs, startMs + 5_000L)
                else -> startMs + 4_500L
            }
            val interlude = rawText.isEmpty() || isInterlude(rawText)
            lines.add(
                LyricLine(
                    text = if (interlude) "♪" else rawText,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    isInterlude = interlude
                )
            )
        }

        return TrackLyrics(
            videoId = videoId,
            title = title,
            artist = artist,
            durationMs = durationMs,
            lines = lines,
            isSynced = true,
            sourceMessage = sourceMessage
        )
    }

    fun parseTtml(
        ttmlText: String,
        title: String,
        artist: String,
        videoId: String? = null,
        durationMs: Long = 0L,
        sourceMessage: String? = null
    ): TrackLyrics? {
        val matches = TTML_P_REGEX.findAll(ttmlText).toList()
        if (matches.isEmpty()) return null

        val parsed = mutableListOf<LyricLine>()
        for (match in matches) {
            val attrs = match.groupValues[1]
            val beginRaw = TTML_BEGIN_ATTR_REGEX.find(attrs)?.groupValues?.get(1) ?: continue
            val beginMs = parseClockTimestampMs(beginRaw) ?: continue
            val endRaw = TTML_END_ATTR_REGEX.find(attrs)?.groupValues?.get(1)
            val endMs = endRaw?.takeIf { it.isNotBlank() }?.let(::parseClockTimestampMs)
                ?: (beginMs + 4_000L)
            val body = decodeXmlEntities(match.groupValues[2].replace(HTML_TAG_REGEX, " "))
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (body.isEmpty()) continue
            val interlude = isInterlude(body)
            parsed.add(
                LyricLine(
                    text = if (interlude) "♪" else body,
                    startTimeMs = beginMs,
                    endTimeMs = maxOf(endMs, beginMs),
                    isInterlude = interlude
                )
            )
        }

        if (parsed.isEmpty()) return null
        return TrackLyrics(
            videoId = videoId,
            title = title,
            artist = artist,
            durationMs = durationMs,
            lines = parsed.sortedBy { it.startTimeMs },
            isSynced = true,
            sourceMessage = sourceMessage
        )
    }

    internal fun parseClockTimestampMs(raw: String): Long? {
        val value = raw.trim()
        if (value.endsWith("s", ignoreCase = true) && !value.contains(':')) {
            val secs = value.dropLast(1).toDoubleOrNull() ?: return null
            return (secs * 1000.0).roundToLong().coerceAtLeast(0L)
        }
        val parts = value.split(':')
        return when (parts.size) {
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                (m * 60_000L + (s * 1000.0).roundToLong()).coerceAtLeast(0L)
            }
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                (h * 3_600_000L + m * 60_000L + (s * 1000.0).roundToLong()).coerceAtLeast(0L)
            }
            else -> value.toDoubleOrNull()?.let { (it * 1000.0).roundToLong().coerceAtLeast(0L) }
        }
    }

    private fun decodeXmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
    }

    private fun isInterlude(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && t.all { it == '♪' || it == '♫' || it == '♬' || it == '•' || it.isWhitespace() }
    }
}
