package com.hyperlyrics.mm.xposed.ytm

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.ipc.LyricsBridgeClient
import com.hyperlyrics.mm.lyrics.model.LyricLine
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.ytm.YouTubeMusicInnertubeClient
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.concurrent.Executors

/**
 * Hook installed inside YouTube Music (`com.google.android.apps.youtube.music` & RVX/ReVanced).
 *
 * Responsibilities:
 *  1. Captures exact `videoId` from active MediaSession metadata & YouTube URIs.
 *  2. Scans YouTube Music Premium offline downloads DB so our app automatically pre-caches
 *     and permanently pins official timed lyrics while online.
 *  3. Extracts offline lyrics directly from YouTube Music's own local SQLite entity store
 *     AND live Lyrics tab TextViews so Premium downloaded songs display lyrics 100% offline.
 */
object YouTubeMusicProcessHooker {
    private const val TAG = "YtmProcessHook"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HyperLyricsMM-YtmReporter").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastReportedId: String? = null

    @Volatile
    private var currentTitle: String = ""

    @Volatile
    private var currentArtist: String = ""

    @Volatile
    private var currentDurationMs: Long = 0L

    @Volatile
    private var lastOfflineSyncedKey: String? = null

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val appClass = classLoader.loadClass("android.app.Application")
            val onCreate = appClass.getDeclaredMethod("onCreate")
            module.hook(onCreate).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val result = chain.proceed()
                    (chain.thisObject as? Application)?.let { app ->
                        appContext = app.applicationContext
                        registerOfflineScanReceiver(app.applicationContext)
                        // Background-scan Premium downloads 7s after YTM cold start
                        mainHandler.postDelayed({ scheduleOfflineLibrarySync() }, 7_000L)
                    }
                    return result
                }
            })
        }.onFailure {
            ModuleLogger.w(TAG, "Failed hooking Application.onCreate in YTM", it)
        }

        // Hook MediaSession.setMetadata to immediately capture videoId + trigger offline extraction
        runCatching {
            val setMetadata = MediaSession::class.java.getDeclaredMethod(
                "setMetadata",
                MediaMetadata::class.java
            )
            module.hook(setMetadata).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val metadata = chain.args.firstOrNull() as? MediaMetadata
                    if (metadata != null) {
                        inspectAndReportMetadata(metadata)
                    }
                    return chain.proceed()
                }
            })
            ModuleLogger.i(TAG, "YouTube Music MediaSession metadata hook installed")
        }.onFailure {
            ModuleLogger.w(TAG, "Failed hooking MediaSession.setMetadata in YTM", it)
        }

        // Hook Uri.parse to opportunistically detect YouTube watch/thumbnail videoIds
        runCatching {
            val uriClass = classLoader.loadClass("android.net.Uri")
            val parseMethod = uriClass.getDeclaredMethod("parse", String::class.java)
            module.hook(parseMethod).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val uriStr = chain.args.firstOrNull() as? String
                    if (uriStr != null &&
                        (uriStr.contains("i.ytimg.com/vi") || uriStr.contains("music.youtube.com/watch"))
                    ) {
                        val vid = YouTubeMusicInnertubeClient.extractVideoIdFromUri(uriStr)
                        if (vid != null && vid != lastReportedId) {
                            reportVideoIdAsync(vid, currentTitle, currentArtist)
                        }
                    }
                    return chain.proceed()
                }
            })
        }.onFailure {
            ModuleLogger.d(TAG, "Optional Uri.parse videoId observer skipped: ${it.message}")
        }

        // Hook TextView.setText lightly to capture multi-line offline lyrics if user opens Lyrics tab
        runCatching {
            val setText = TextView::class.java.getDeclaredMethod(
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java
            )
            module.hook(setText).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val text = chain.args.firstOrNull()?.toString()
                    if (text != null && text.length in 100..20_000 && text.count { it == '\n' } >= 5) {
                        maybeCaptureDisplayedOfflineLyrics(text)
                    }
                    return chain.proceed()
                }
            })
        }.onFailure {
            ModuleLogger.d(TAG, "Optional offline Lyrics TextView observer skipped: ${it.message}")
        }
    }

    private fun registerOfflineScanReceiver(context: Context) {
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        scheduleOfflineLibrarySync()
                    }
                },
                IntentFilter(ModuleConfig.ACTION_REQUEST_YTM_OFFLINE_SCAN),
                Context.RECEIVER_EXPORTED
            )
        }
    }

    private fun scheduleOfflineLibrarySync() {
        val ctx = appContext ?: return
        executor.execute {
            runCatching {
                val records = YouTubeMusicOfflineExtractor.scanPremiumDownloadedTracks(ctx)
                if (records.isNotEmpty()) {
                    LyricsBridgeClient.syncOfflineLibrary(
                        context = ctx,
                        videoIds = records.map { it.videoId },
                        titles = records.map { it.title },
                        artists = records.map { it.artist }
                    )
                }
            }.onFailure {
                ModuleLogger.w(TAG, "Offline library scan failed", it)
            }
        }
    }

    private fun inspectAndReportMetadata(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)

        if (title.isNotEmpty()) {
            currentTitle = title
            currentArtist = artist
            currentDurationMs = duration
        }

        val candidateIds = listOf(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            YouTubeMusicInnertubeClient.extractVideoIdFromUri(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ),
            YouTubeMusicInnertubeClient.extractVideoIdFromUri(
                metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ),
            YouTubeMusicInnertubeClient.extractVideoIdFromUri(
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            )
        )
        val videoId = candidateIds.firstOrNull { YouTubeMusicInnertubeClient.isValidVideoId(it) }?.trim()

        if (videoId != null) {
            reportVideoIdAsync(videoId, title, artist)
        }

        // When offline (or for downloaded Premium tracks), proactively extract from YTM's local SQLite DB
        val ctx = appContext ?: return
        if (title.isNotEmpty() && !isNetworkConnected(ctx)) {
            val trackKey = "${videoId.orEmpty()}|$title|$artist"
            if (trackKey != lastOfflineSyncedKey) {
                lastOfflineSyncedKey = trackKey
                executor.execute {
                    val extracted = YouTubeMusicOfflineExtractor.extractOfflineLyricsForTrack(
                        context = ctx,
                        videoId = videoId ?: lastReportedId,
                        title = title,
                        artist = artist,
                        durationMs = duration
                    )
                    if (extracted != null && extracted.hasVocalLines) {
                        LyricsBridgeClient.reportPinnedTrackLyrics(ctx, extracted)
                    }
                }
            }
        }
    }

    private fun maybeCaptureDisplayedOfflineLyrics(text: String) {
        val ctx = appContext ?: return
        val title = currentTitle
        if (title.isBlank()) return
        if (text.contains("http://") || text.contains("https://")) return
        val rawLines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rawLines.size < 6) return
        val avgLen = rawLines.map { it.length }.average()
        if (avgLen !in 8.0..90.0) return

        val key = "ui|$title|$currentArtist|${rawLines.size}"
        if (key == lastOfflineSyncedKey) return
        lastOfflineSyncedKey = key

        val track = TrackLyrics(
            videoId = lastReportedId,
            title = title,
            artist = currentArtist,
            durationMs = currentDurationMs,
            lines = rawLines.mapIndexed { idx, line ->
                LyricLine(text = line, startTimeMs = idx * 1000L, endTimeMs = (idx + 1) * 1000L)
            },
            isSynced = false,
            sourceMessage = "YouTube Music Offline"
        )
        executor.execute {
            LyricsBridgeClient.reportPinnedTrackLyrics(ctx, track)
        }
    }

    private fun reportVideoIdAsync(videoId: String, title: String, artist: String) {
        val ctx = appContext ?: return
        if (videoId == lastReportedId && title.isEmpty()) return
        lastReportedId = videoId
        executor.execute {
            LyricsBridgeClient.reportVideoId(ctx, videoId, title, artist)
        }
    }

    private fun isNetworkConnected(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }
}
