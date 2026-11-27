package com.hyperlyrics.mm.lyrics.model

import kotlinx.serialization.Serializable

@Serializable
data class LyricLine(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val isInterlude: Boolean = false
)

@Serializable
data class TrackLyrics(
    val videoId: String? = null,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L,
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val sourceMessage: String? = null,
    val fetchedAtMs: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean
        get() = lines.isEmpty()

    val hasVocalLines: Boolean
        get() = lines.any { !it.isInterlude && it.text.isNotBlank() }
}

sealed interface LyricsState {
    data object Idle : LyricsState
    data class Loading(val title: String, val artist: String) : LyricsState
    data class Ready(val lyrics: TrackLyrics) : LyricsState
    data class Unavailable(val title: String, val artist: String, val reason: String) : LyricsState
}

data class ActiveLyricSnapshot(
    val activeIndex: Int,
    val currentText: String,
    val nextText: String,
    val isInterlude: Boolean,
    val interludeProgress: Float, // 0f..1f during instrumental gaps
    val lineProgress: Float // 0f..1f within current active line
) {
    companion object {
        val EMPTY = ActiveLyricSnapshot(
            activeIndex = -1,
            currentText = "",
            nextText = "",
            isInterlude = false,
            interludeProgress = 0f,
            lineProgress = 0f
        )
    }
}
