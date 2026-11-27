package com.hyperlyrics.mm.ipc

import android.content.Context
import android.os.Bundle
import com.hyperlyrics.mm.common.AccentColorMode
import com.hyperlyrics.mm.common.AppLanguage
import com.hyperlyrics.mm.common.ExpandedThemeStyle
import com.hyperlyrics.mm.common.MinimalLyricSlot
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * IPC client used inside SystemUI and YouTube Music processes to communicate with
 * [LyricsBridgeProvider] hosted in the HyperLyricsMM app process.
 */
object LyricsBridgeClient {
    private const val TAG = "LyricsBridgeClient"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun fetchLyrics(
        context: Context,
        videoId: String?,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L,
        forceRefresh: Boolean = false
    ): TrackLyrics? {
        return runCatching {
            val extras = Bundle().apply {
                if (!videoId.isNullOrBlank()) putString(LyricsBridgeProvider.EXTRA_VIDEO_ID, videoId)
                putString(LyricsBridgeProvider.EXTRA_TITLE, title)
                putString(LyricsBridgeProvider.EXTRA_ARTIST, artist)
                putString(LyricsBridgeProvider.EXTRA_ALBUM, album)
                putLong(LyricsBridgeProvider.EXTRA_DURATION_MS, durationMs)
                putBoolean(LyricsBridgeProvider.EXTRA_FORCE_REFRESH, forceRefresh)
            }
            val reply = context.contentResolver.call(
                LyricsBridgeProvider.AUTHORITY_URI,
                LyricsBridgeProvider.METHOD_FETCH_LYRICS,
                null,
                extras
            ) ?: return null

            if (!reply.getBoolean(LyricsBridgeProvider.EXTRA_OK, false)) return null
            val payload = reply.getString(LyricsBridgeProvider.EXTRA_LYRICS_JSON) ?: return null
            json.decodeFromString<TrackLyrics>(payload)
        }.onFailure { error ->
            ModuleLogger.w(TAG, "IPC fetchLyrics failed", error)
        }.getOrNull()
    }

    fun reportVideoId(
        context: Context,
        videoId: String,
        title: String = "",
        artist: String = ""
    ) {
        runCatching {
            val extras = Bundle().apply {
                putString(LyricsBridgeProvider.EXTRA_VIDEO_ID, videoId)
                putString(LyricsBridgeProvider.EXTRA_TITLE, title)
                putString(LyricsBridgeProvider.EXTRA_ARTIST, artist)
            }
            context.contentResolver.call(
                LyricsBridgeProvider.AUTHORITY_URI,
                LyricsBridgeProvider.METHOD_REPORT_VIDEO_ID,
                null,
                extras
            )
        }.onFailure { error ->
            ModuleLogger.d(TAG, "reportVideoId skipped: ${error.message}")
        }
    }

    fun reportPinnedTrackLyrics(context: Context, lyrics: TrackLyrics) {
        runCatching {
            val extras = Bundle().apply {
                putString(LyricsBridgeProvider.EXTRA_LYRICS_JSON, json.encodeToString(lyrics))
            }
            context.contentResolver.call(
                LyricsBridgeProvider.AUTHORITY_URI,
                LyricsBridgeProvider.METHOD_REPORT_PINNED_LYRICS,
                null,
                extras
            )
        }.onFailure { error ->
            ModuleLogger.d(TAG, "reportPinnedTrackLyrics skipped: ${error.message}")
        }
    }

    fun syncOfflineLibrary(
        context: Context,
        videoIds: List<String>,
        titles: List<String>,
        artists: List<String>
    ) {
        if (videoIds.isEmpty()) return
        runCatching {
            val extras = Bundle().apply {
                putStringArrayList(LyricsBridgeProvider.EXTRA_VIDEO_IDS, ArrayList(videoIds))
                putStringArrayList(LyricsBridgeProvider.EXTRA_TITLES, ArrayList(titles))
                putStringArrayList(LyricsBridgeProvider.EXTRA_ARTISTS, ArrayList(artists))
            }
            context.contentResolver.call(
                LyricsBridgeProvider.AUTHORITY_URI,
                LyricsBridgeProvider.METHOD_SYNC_OFFLINE_LIBRARY,
                null,
                extras
            )
        }.onFailure { error ->
            ModuleLogger.d(TAG, "syncOfflineLibrary skipped: ${error.message}")
        }
    }

    fun fetchSettingsFallback(context: Context): ModuleSettings? {
        return runCatching {
            val reply = context.contentResolver.call(
                LyricsBridgeProvider.AUTHORITY_URI,
                LyricsBridgeProvider.METHOD_GET_SETTINGS,
                null,
                null
            ) ?: return null
            if (reply.isEmpty) return null
            ModuleSettings(
                masterEnabled = reply.getBoolean(ModuleConfig.KEY_MASTER_ENABLED, true),
                appLanguage = AppLanguage.fromId(
                    reply.getInt(ModuleConfig.KEY_APP_LANGUAGE, AppLanguage.SYSTEM.id)
                ),
                expandedLyricsEnabled = reply.getBoolean(ModuleConfig.KEY_EXPANDED_LYRICS_ENABLED, true),
                pinExpandedIslandEnabled = reply.getBoolean(ModuleConfig.KEY_PIN_EXPANDED_ISLAND_ENABLED, false),
                showPinButtonOnIsland = reply.getBoolean(ModuleConfig.KEY_SHOW_PIN_BUTTON_ON_ISLAND, true),
                expandedThemeStyle = ExpandedThemeStyle.fromId(
                    reply.getInt(ModuleConfig.KEY_EXPANDED_THEME_STYLE, ExpandedThemeStyle.YTM_DYNAMIC.id)
                ),
                accentColorMode = AccentColorMode.fromId(
                    reply.getInt(ModuleConfig.KEY_ACCENT_COLOR_MODE, AccentColorMode.YTM_CRIMSON.id)
                ),
                leftPaneRatioPercent = reply.getInt(ModuleConfig.KEY_LEFT_PANE_RATIO_PERCENT, 45).coerceIn(38, 52),
                expandedFontSizeSp = reply.getFloat(ModuleConfig.KEY_EXPANDED_FONT_SIZE_SP, 14.5f).coerceIn(11f, 18f),
                showLikeButton = reply.getBoolean(ModuleConfig.KEY_SHOW_LIKE_BUTTON, true),
                tapLyricToSeek = reply.getBoolean(ModuleConfig.KEY_TAP_LYRIC_TO_SEEK, true),
                showInterludeDots = reply.getBoolean(ModuleConfig.KEY_SHOW_INTERLUDE_DOTS, true),
                minimalLyricsEnabled = reply.getBoolean(ModuleConfig.KEY_MINIMAL_LYRICS_ENABLED, true),
                minimalSlot = MinimalLyricSlot.fromId(
                    reply.getInt(ModuleConfig.KEY_MINIMAL_SLOT, MinimalLyricSlot.RIGHT.id)
                ),
                minimalFontSizeSp = reply.getFloat(ModuleConfig.KEY_MINIMAL_FONT_SIZE_SP, 11.0f).coerceIn(9f, 13.5f),
                minimalMaxWidthDp = reply.getInt(ModuleConfig.KEY_MINIMAL_MAX_WIDTH_DP, 132).coerceIn(80, 200),
                unlockIslandWidthLimit = reply.getBoolean(ModuleConfig.KEY_UNLOCK_ISLAND_WIDTH_LIMIT, true),
                offlinePremiumSyncEnabled = reply.getBoolean(ModuleConfig.KEY_OFFLINE_PREMIUM_SYNC_ENABLED, true),
                offlineYtmDbReadEnabled = reply.getBoolean(ModuleConfig.KEY_OFFLINE_YTM_DB_READ_ENABLED, true),
                externalFallbackEnabled = reply.getBoolean(ModuleConfig.KEY_EXTERNAL_FALLBACK_ENABLED, false),
                fallbackLrclibEnabled = reply.getBoolean(ModuleConfig.KEY_FALLBACK_LRCLIB_ENABLED, true),
                fallbackYtCaptionsEnabled = reply.getBoolean(ModuleConfig.KEY_FALLBACK_YT_CAPTIONS_ENABLED, true),
                fallbackNeteaseEnabled = reply.getBoolean(ModuleConfig.KEY_FALLBACK_NETEASE_ENABLED, false),
                localLrcFolderEnabled = reply.getBoolean(ModuleConfig.KEY_LOCAL_LRC_FOLDER_ENABLED, false),
                localLrcTreeUri = reply.getString(ModuleConfig.KEY_LOCAL_LRC_TREE_URI, "").orEmpty(),
                localSpeechExtractorEnabled = reply.getBoolean(ModuleConfig.KEY_LOCAL_SPEECH_EXTRACTOR_ENABLED, false),
                syncOffsetMs = reply.getInt(ModuleConfig.KEY_SYNC_OFFSET_MS, 0).coerceIn(-1500, 1500)
            )
        }.getOrNull()
    }
}
