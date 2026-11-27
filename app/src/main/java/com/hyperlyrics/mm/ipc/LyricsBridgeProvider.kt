package com.hyperlyrics.mm.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.lyrics.LyricsResolver
import com.hyperlyrics.mm.lyrics.cache.LyricsCacheDatabase
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.ytm.YouTubeMusicInnertubeClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exported IPC ContentProvider running inside the HyperLyricsMM app process.
 *
 * Bridges SystemUI and YouTube Music hooks with:
 *  - Unified [LyricsResolver] (pinned offline SQLite cache, local .lrc/.ttml folder,
 *    direct YouTube Music Innertube, and optional external fallback providers)
 *  - Background YouTube Music Premium offline downloads pre-caching & permanent pinning
 *  - Direct offline lyrics ingestion from YouTube Music's own process
 */
class LyricsBridgeProvider : ContentProvider() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private lateinit var cacheDb: LyricsCacheDatabase
    private lateinit var resolver: LyricsResolver

    private val prefetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HyperLyricsMM-OfflinePrefetch").apply { isDaemon = true }
    }
    private val prefetchRunning = AtomicBoolean(false)

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        cacheDb = LyricsCacheDatabase(ctx)
        resolver = LyricsResolver(ctx, cacheDb)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_REPORT_VIDEO_ID -> handleReportVideoId(extras)
            METHOD_REPORT_DIRECT_BROWSE_JSON -> handleReportDirectBrowseJson(extras)
            METHOD_REPORT_PINNED_LYRICS -> handleReportPinnedLyrics(extras)
            METHOD_SYNC_OFFLINE_LIBRARY -> handleSyncOfflineLibrary(extras)
            METHOD_FETCH_LYRICS -> handleFetchLyrics(extras)
            METHOD_GET_SETTINGS -> handleGetSettings()
            METHOD_GET_CACHE_COUNT -> Bundle().apply {
                putInt(EXTRA_COUNT, cacheDb.count())
                putInt(EXTRA_PINNED_COUNT, cacheDb.pinnedCount())
            }
            else -> Bundle()
        }
    }

    private fun handleReportVideoId(extras: Bundle?): Bundle {
        val videoId = extras?.getString(EXTRA_VIDEO_ID)?.trim()
        if (YouTubeMusicInnertubeClient.isValidVideoId(videoId) && videoId != null) {
            val title = extras.getString(EXTRA_TITLE).orEmpty().trim()
            val artist = extras.getString(EXTRA_ARTIST).orEmpty().trim()
            lastReportedVideoId = ReportedVideo(
                videoId = videoId,
                title = title,
                artist = artist,
                timestampMs = System.currentTimeMillis()
            )
            val trackKey = LyricsCacheDatabase.makeTrackKey(title, artist)
            if (trackKey.isNotEmpty()) {
                videoIdByTrackKey[trackKey] = videoId
            }
            ModuleLogger.d(TAG, "YTM hook reported videoId=$videoId title=$title")
        }
        return Bundle().apply { putBoolean(EXTRA_OK, true) }
    }

    private fun handleReportDirectBrowseJson(extras: Bundle?): Bundle {
        val rawJson = extras?.getString(EXTRA_RAW_JSON) ?: return Bundle()
        val videoId = extras.getString(EXTRA_VIDEO_ID) ?: lastReportedVideoId?.videoId
        val title = extras.getString(EXTRA_TITLE) ?: lastReportedVideoId?.title.orEmpty()
        val artist = extras.getString(EXTRA_ARTIST) ?: lastReportedVideoId?.artist.orEmpty()
        val durationMs = extras.getLong(EXTRA_DURATION_MS, 0L)

        val parsed = runCatching {
            YouTubeMusicInnertubeClient.parseBrowseLyricsResponse(
                json = JSONObject(rawJson),
                videoId = videoId,
                title = title,
                artist = artist,
                durationMs = durationMs
            )
        }.getOrNull()

        if (parsed != null && parsed.hasVocalLines) {
            cacheDb.put(parsed, pinned = false)
            broadcastLyricsUpdated()
            ModuleLogger.i(TAG, "Cached direct YTM lyrics (${parsed.lines.size} lines)")
        }
        return Bundle().apply { putBoolean(EXTRA_OK, parsed != null) }
    }

    private fun handleReportPinnedLyrics(extras: Bundle?): Bundle {
        val payload = extras?.getString(EXTRA_LYRICS_JSON) ?: return Bundle()
        val parsed = runCatching { json.decodeFromString<TrackLyrics>(payload) }.getOrNull()
        if (parsed != null && parsed.hasVocalLines) {
            cacheDb.put(parsed, pinned = true)
            broadcastLyricsUpdated()
            ModuleLogger.i(TAG, "Pinned offline lyrics (${parsed.lines.size} lines) for ${parsed.title}")
            return Bundle().apply { putBoolean(EXTRA_OK, true) }
        }
        return Bundle().apply { putBoolean(EXTRA_OK, false) }
    }

    private fun handleSyncOfflineLibrary(extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle()
        val settings = readCurrentSettings(ctx)
        if (!settings.offlinePremiumSyncEnabled) {
            return Bundle().apply { putBoolean(EXTRA_OK, false) }
        }
        val videoIds = extras?.getStringArrayList(EXTRA_VIDEO_IDS).orEmpty()
        val titles = extras?.getStringArrayList(EXTRA_TITLES).orEmpty()
        val artists = extras?.getStringArrayList(EXTRA_ARTISTS).orEmpty()
        if (videoIds.isEmpty()) return Bundle().apply { putBoolean(EXTRA_OK, true) }

        videoIds.forEachIndexed { idx, vid ->
            val t = titles.getOrNull(idx).orEmpty()
            val a = artists.getOrNull(idx).orEmpty()
            val key = LyricsCacheDatabase.makeTrackKey(t, a)
            if (key.isNotEmpty() && YouTubeMusicInnertubeClient.isValidVideoId(vid)) {
                videoIdByTrackKey[key] = vid
            }
        }

        if (prefetchRunning.compareAndSet(false, true)) {
            prefetchExecutor.execute {
                try {
                    var newlyPinned = 0
                    for (i in videoIds.indices) {
                        val vid = videoIds[i]
                        val title = titles.getOrNull(i).orEmpty()
                        val artist = artists.getOrNull(i).orEmpty()
                        if (cacheDb.hasLyrics(vid, title, artist)) {
                            cacheDb.markPinnedByVideoId(vid)
                            continue
                        }
                        val fetched = resolver.resolve(
                            videoId = vid,
                            title = title,
                            artist = artist,
                            settings = readCurrentSettings(ctx),
                            forceRefresh = false,
                            pinIfFound = true
                        )
                        if (fetched != null && fetched.hasVocalLines) {
                            newlyPinned++
                        }
                        // Polite pacing to avoid hammering YouTube Music API
                        runCatching { Thread.sleep(380L) }
                    }
                    if (newlyPinned > 0) {
                        ModuleLogger.i(TAG, "Offline Premium pre-cache pinned $newlyPinned new tracks")
                        broadcastLyricsUpdated()
                    }
                } finally {
                    prefetchRunning.set(false)
                }
            }
        }
        return Bundle().apply { putBoolean(EXTRA_OK, true) }
    }

    private fun handleFetchLyrics(extras: Bundle?): Bundle {
        val result = Bundle()
        val ctx = context ?: return result
        if (extras == null) return result

        val title = extras.getString(EXTRA_TITLE).orEmpty().trim()
        val artist = extras.getString(EXTRA_ARTIST).orEmpty().trim()
        val album = extras.getString(EXTRA_ALBUM).orEmpty().trim()
        val durationMs = extras.getLong(EXTRA_DURATION_MS, 0L)
        val forceRefresh = extras.getBoolean(EXTRA_FORCE_REFRESH, false)

        val explicitVideoId = extras.getString(EXTRA_VIDEO_ID)?.trim()
            ?.takeIf { YouTubeMusicInnertubeClient.isValidVideoId(it) }
        val trackKey = LyricsCacheDatabase.makeTrackKey(title, artist)
        val mappedVideoId = trackKey.takeIf { it.isNotEmpty() }?.let { videoIdByTrackKey[it] }
        val recentVideoId = lastReportedVideoId?.takeIf { recent ->
            System.currentTimeMillis() - recent.timestampMs < 25_000L &&
                (recent.title.isBlank() || recent.title.equals(title, ignoreCase = true))
        }?.videoId

        val resolvedVideoId = explicitVideoId ?: mappedVideoId ?: recentVideoId
        val settings = readCurrentSettings(ctx)

        val fetched = resolver.resolve(
            videoId = resolvedVideoId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            settings = settings,
            forceRefresh = forceRefresh,
            pinIfFound = false
        )

        if (fetched != null && fetched.hasVocalLines) {
            result.putBoolean(EXTRA_OK, true)
            result.putString(EXTRA_LYRICS_JSON, json.encodeToString(fetched))
        } else {
            result.putBoolean(EXTRA_OK, false)
            result.putString(EXTRA_ERROR, "no_lyrics")
        }
        return result
    }

    private fun handleGetSettings(): Bundle {
        val ctx = context ?: return Bundle()
        val s = readCurrentSettings(ctx)
        return Bundle().apply {
            putBoolean(ModuleConfig.KEY_MASTER_ENABLED, s.masterEnabled)
            putInt(ModuleConfig.KEY_APP_LANGUAGE, s.appLanguage.id)
            putBoolean(ModuleConfig.KEY_EXPANDED_LYRICS_ENABLED, s.expandedLyricsEnabled)
            putInt(ModuleConfig.KEY_EXPANDED_THEME_STYLE, s.expandedThemeStyle.id)
            putInt(ModuleConfig.KEY_ACCENT_COLOR_MODE, s.accentColorMode.id)
            putInt(ModuleConfig.KEY_LEFT_PANE_RATIO_PERCENT, s.leftPaneRatioPercent)
            putFloat(ModuleConfig.KEY_EXPANDED_FONT_SIZE_SP, s.expandedFontSizeSp)
            putBoolean(ModuleConfig.KEY_SHOW_LIKE_BUTTON, s.showLikeButton)
            putBoolean(ModuleConfig.KEY_TAP_LYRIC_TO_SEEK, s.tapLyricToSeek)
            putBoolean(ModuleConfig.KEY_SHOW_INTERLUDE_DOTS, s.showInterludeDots)
            putBoolean(ModuleConfig.KEY_MINIMAL_LYRICS_ENABLED, s.minimalLyricsEnabled)
            putInt(ModuleConfig.KEY_MINIMAL_SLOT, s.minimalSlot.id)
            putFloat(ModuleConfig.KEY_MINIMAL_FONT_SIZE_SP, s.minimalFontSizeSp)
            putInt(ModuleConfig.KEY_MINIMAL_MAX_WIDTH_DP, s.minimalMaxWidthDp)
            putBoolean(ModuleConfig.KEY_UNLOCK_ISLAND_WIDTH_LIMIT, s.unlockIslandWidthLimit)
            putBoolean(ModuleConfig.KEY_OFFLINE_PREMIUM_SYNC_ENABLED, s.offlinePremiumSyncEnabled)
            putBoolean(ModuleConfig.KEY_OFFLINE_YTM_DB_READ_ENABLED, s.offlineYtmDbReadEnabled)
            putBoolean(ModuleConfig.KEY_EXTERNAL_FALLBACK_ENABLED, s.externalFallbackEnabled)
            putBoolean(ModuleConfig.KEY_FALLBACK_LRCLIB_ENABLED, s.fallbackLrclibEnabled)
            putBoolean(ModuleConfig.KEY_FALLBACK_YT_CAPTIONS_ENABLED, s.fallbackYtCaptionsEnabled)
            putBoolean(ModuleConfig.KEY_FALLBACK_NETEASE_ENABLED, s.fallbackNeteaseEnabled)
            putBoolean(ModuleConfig.KEY_LOCAL_LRC_FOLDER_ENABLED, s.localLrcFolderEnabled)
            putString(ModuleConfig.KEY_LOCAL_LRC_TREE_URI, s.localLrcTreeUri)
            putBoolean(ModuleConfig.KEY_LOCAL_SPEECH_EXTRACTOR_ENABLED, s.localSpeechExtractorEnabled)
            putInt(ModuleConfig.KEY_SYNC_OFFSET_MS, s.syncOffsetMs)
        }
    }

    private fun readCurrentSettings(ctx: Context) =
        ModuleConfig.readSettings(ctx.getSharedPreferences(ModuleConfig.PREFS_NAME, Context.MODE_PRIVATE))

    private fun broadcastLyricsUpdated() {
        runCatching {
            context?.sendBroadcast(Intent(ModuleConfig.ACTION_LYRICS_UPDATED))
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private data class ReportedVideo(
        val videoId: String,
        val title: String,
        val artist: String,
        val timestampMs: Long
    )

    companion object {
        private const val TAG = "LyricsBridgeProvider"
        val AUTHORITY_URI: Uri = Uri.parse("content://${ModuleConfig.BRIDGE_AUTHORITY}")

        const val METHOD_FETCH_LYRICS = "fetchLyrics"
        const val METHOD_REPORT_VIDEO_ID = "reportVideoId"
        const val METHOD_REPORT_DIRECT_BROWSE_JSON = "reportDirectBrowseJson"
        const val METHOD_REPORT_PINNED_LYRICS = "reportPinnedLyrics"
        const val METHOD_SYNC_OFFLINE_LIBRARY = "syncOfflineLibrary"
        const val METHOD_GET_SETTINGS = "getSettings"
        const val METHOD_GET_CACHE_COUNT = "getCacheCount"

        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_VIDEO_IDS = "video_ids"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ARTISTS = "artists"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_FORCE_REFRESH = "force_refresh"
        const val EXTRA_RAW_JSON = "raw_json"
        const val EXTRA_LYRICS_JSON = "lyrics_json"
        const val EXTRA_OK = "ok"
        const val EXTRA_ERROR = "error"
        const val EXTRA_COUNT = "count"
        const val EXTRA_PINNED_COUNT = "pinned_count"

        @Volatile
        private var lastReportedVideoId: ReportedVideo? = null
        private val videoIdByTrackKey = ConcurrentHashMap<String, String>()
    }
}
