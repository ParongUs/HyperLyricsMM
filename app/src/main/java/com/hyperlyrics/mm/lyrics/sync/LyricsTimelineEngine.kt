package com.hyperlyrics.mm.lyrics.sync

import com.hyperlyrics.mm.lyrics.model.ActiveLyricSnapshot
import com.hyperlyrics.mm.lyrics.model.LyricLine
import com.hyperlyrics.mm.lyrics.model.TrackLyrics

/**
 * Precision playback-to-lyric synchronization engine.
 * Handles synced YouTube Music cue ranges, instrumental gaps, countdown progress,
 * and proportional fallback scrolling for unsynced static lyrics.
 */
object LyricsTimelineEngine {
    private const val MIN_INTERLUDE_GAP_MS = 3200L

    fun resolve(
        lyrics: TrackLyrics?,
        rawPositionMs: Long,
        offsetMs: Int = 0,
        durationMs: Long = 0L
    ): ActiveLyricSnapshot {
        if (lyrics == null || lyrics.lines.isEmpty()) return ActiveLyricSnapshot.EMPTY

        val lines = lyrics.lines
        val effectivePos = (rawPositionMs + offsetMs).coerceAtLeast(0L)

        if (!lyrics.isSynced) {
            return resolveUnsynced(lines, effectivePos, durationMs.takeIf { it > 0L } ?: lyrics.durationMs)
        }

        // Before the very first timed lyric line
        val firstLine = lines.first()
        if (effectivePos < firstLine.startTimeMs) {
            val isLeadInInterlude = firstLine.startTimeMs >= MIN_INTERLUDE_GAP_MS
            val progress = if (firstLine.startTimeMs > 0L) {
                (effectivePos.toFloat() / firstLine.startTimeMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            return ActiveLyricSnapshot(
                activeIndex = if (firstLine.isInterlude) 0 else -1,
                currentText = if (firstLine.isInterlude) firstLine.text else "",
                nextText = findNextVocalText(lines, -1),
                isInterlude = isLeadInInterlude || firstLine.isInterlude,
                interludeProgress = progress,
                lineProgress = 0f
            )
        }

        // Binary search for the latest line with startTimeMs <= effectivePos
        var lo = 0
        var hi = lines.lastIndex
        var activeIdx = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startTimeMs <= effectivePos) {
                activeIdx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        val current = lines[activeIdx]
        val next = lines.getOrNull(activeIdx + 1)

        // Check if we are in a silent gap AFTER current.endTimeMs and BEFORE next.startTimeMs
        if (current.endTimeMs > current.startTimeMs &&
            effectivePos > current.endTimeMs &&
            next != null &&
            next.startTimeMs - current.endTimeMs >= MIN_INTERLUDE_GAP_MS
        ) {
            val gapDuration = (next.startTimeMs - current.endTimeMs).coerceAtLeast(1L)
            val gapProgress = ((effectivePos - current.endTimeMs).toFloat() / gapDuration.toFloat())
                .coerceIn(0f, 1f)
            return ActiveLyricSnapshot(
                activeIndex = activeIdx,
                currentText = "",
                nextText = findNextVocalText(lines, activeIdx),
                isInterlude = true,
                interludeProgress = gapProgress,
                lineProgress = 1f
            )
        }

        val lineDuration = resolveLineDuration(current, next)
        val lineProgress = if (lineDuration > 0L) {
            ((effectivePos - current.startTimeMs).toFloat() / lineDuration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        return ActiveLyricSnapshot(
            activeIndex = activeIdx,
            currentText = if (current.isInterlude) "" else current.text,
            nextText = findNextVocalText(lines, activeIdx),
            isInterlude = current.isInterlude,
            interludeProgress = if (current.isInterlude) lineProgress else 0f,
            lineProgress = lineProgress
        )
    }

    private fun resolveUnsynced(
        lines: List<LyricLine>,
        positionMs: Long,
        durationMs: Long
    ): ActiveLyricSnapshot {
        if (durationMs <= 0L) {
            return ActiveLyricSnapshot(
                activeIndex = 0,
                currentText = lines.first().text,
                nextText = lines.getOrNull(1)?.text.orEmpty(),
                isInterlude = false,
                interludeProgress = 0f,
                lineProgress = 0f
            )
        }
        // Map 5%..95% of song duration across static lines smoothly
        val startMargin = (durationMs * 0.05f).toLong()
        val usableDuration = (durationMs * 0.90f).toLong().coerceAtLeast(1L)
        val fraction = ((positionMs - startMargin).toFloat() / usableDuration.toFloat()).coerceIn(0f, 0.9999f)
        val index = (fraction * lines.size).toInt().coerceIn(0, lines.lastIndex)
        val current = lines[index]
        return ActiveLyricSnapshot(
            activeIndex = index,
            currentText = current.text,
            nextText = lines.getOrNull(index + 1)?.text.orEmpty(),
            isInterlude = false,
            interludeProgress = 0f,
            lineProgress = (fraction * lines.size) - index
        )
    }

    private fun resolveLineDuration(current: LyricLine, next: LyricLine?): Long {
        if (current.endTimeMs > current.startTimeMs) {
            return current.endTimeMs - current.startTimeMs
        }
        if (next != null && next.startTimeMs > current.startTimeMs) {
            return next.startTimeMs - current.startTimeMs
        }
        return 4000L
    }

    private fun findNextVocalText(lines: List<LyricLine>, afterIndex: Int): String {
        for (i in (afterIndex + 1) until lines.size) {
            val candidate = lines[i]
            if (!candidate.isInterlude && candidate.text.isNotBlank()) {
                return candidate.text
            }
        }
        return ""
    }
}
