package com.hyperlyrics.mm.xposed.ytm

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.AbstractWindowedCursor
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.ipc.LyricsBridgeClient
import com.hyperlyrics.mm.ipc.LyricsBridgeProvider
import com.hyperlyrics.mm.lyrics.model.LyricLine
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.ytm.YouTubeMusicInnertubeClient
import com.hyperlyrics.mm.lyrics.ytm.YouTubeProtobufLyricsScanner
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Hook installed inside YouTube Music (`com.google.android.apps.youtube.music` & RVX/ReVanced).
 *
 * Responsibilities:
 *  1. Captures exact `videoId` from active MediaSession metadata & YouTube URIs.
 *  2. Scans YouTube Music Premium offline downloads DB so our app automatically pre-caches
 *     and permanently pins official timed lyrics while online.
 *  3. Intercepts offline lyrics directly from YouTube Music's own local SQLite entity store
 *     (both via proactive DB scan AND live `AbstractWindowedCursor.getBlob` interception when
 *     YTM reads its own offline lyrics entity) AND live Lyrics tab RecyclerView/TextViews.
 *  4. Pushes extracted offline lyrics both to `LyricsBridgeProvider` SQLite cache AND directly
 *     to SystemUI via `ACTION_LYRICS_UPDATED` extras so offline playback works even if HyperOS
 *     battery saver restricts background ContentProvider cold-starts.
 */
object YouTubeMusicProcessHooker {
    private const val TAG = "YtmProcessHook"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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
    private var lastOfflineDbScanKey: String? = null

    @Volatile
    private var lastDeliveredSyncedKey: String? = null

    @Volatile
    private var lastDeliveredStaticKey: String? = null

    @Volatile
    private var lastBlobScanAtMs: Long = 0L

    private var pendingRecyclerContainerRef: WeakReference<ViewGroup>? = null
    private val recyclerHarvestRunnable = Runnable {
        val container = pendingRecyclerContainerRef?.get() ?: return@Runnable
        harvestLyricsFromRecyclerContainer(container)
    }

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
                        mainHandler.postDelayed({ scheduleOfflineLibrarySync() }, 7_000L)
                    }
                    return result
                }
            })
        }.onFailure {
            ModuleLogger.w(TAG, "Failed hooking Application.onCreate in YTM", it)
        }

        // 1. Hook MediaSession.setMetadata to immediately capture videoId + trigger offline DB extraction
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

        // 2. Hook AbstractWindowedCursor.getBlob to catch offline synced lyrics blobs the instant YTM reads them!
        runCatching {
            val getBlob = AbstractWindowedCursor::class.java.getDeclaredMethod(
                "getBlob",
                Int::class.javaPrimitiveType!!
            )
            module.hook(getBlob).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val result = chain.proceed()
                    val blob = result as? ByteArray ?: return result
                    if (blob.size in 180..350_000 && currentTitle.isNotBlank()) {
                        val trackKey = currentTrackIdentity()
                        if (trackKey != lastDeliveredSyncedKey) {
                            maybeInspectCursorBlobAsync(blob, trackKey)
                        }
                    }
                    return result
                }
            })
            ModuleLogger.i(TAG, "YouTube Music SQLite cursor blob observer installed")
        }.onFailure {
            ModuleLogger.d(TAG, "Optional SQLite cursor observer skipped: ${it.message}")
        }

        // 3. Hook Uri.parse to opportunistically detect YouTube watch/thumbnail videoIds
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

        // 4. Hook TextView.setText to capture BOTH static multi-line Lyrics AND synced RecyclerView Lyrics tabs!
        runCatching {
            val setText = TextView::class.java.getDeclaredMethod(
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java
            )
            module.hook(setText).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val text = chain.args.firstOrNull()?.toString()
                    val view = chain.thisObject as? TextView
                    val res = chain.proceed()
                    if (text != null && currentTitle.isNotBlank()) {
                        if (text.length in 100..20_000 && text.count { it == '\n' } >= 5) {
                            maybeCaptureDisplayedStaticLyrics(text)
                        } else if (view != null && text.length in 8..120 && !text.contains('\n')) {
                            val trackKey = currentTrackIdentity()
                            if (trackKey != lastDeliveredSyncedKey && trackKey != lastDeliveredStaticKey) {
                                maybeScheduleRecyclerHarvest(view)
                            }
                        }
                    }
                    return res
                }
            })
        }.onFailure {
            ModuleLogger.d(TAG, "Optional offline Lyrics TextView observer skipped: ${it.message}")
        }
    }

    private fun currentTrackIdentity(): String =
        "${currentTitle.lowercase()}|${currentArtist.lowercase()}"

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

        // Proactively scan YTM's local offline SQLite DBs regardless of VoLTE/network flags
        val ctx = appContext ?: return
        if (title.isNotEmpty()) {
            val scanKey = "${videoId.orEmpty()}|${title.lowercase()}|${artist.lowercase()}"
            if (scanKey != lastOfflineDbScanKey) {
                lastOfflineDbScanKey = scanKey
                executor.execute {
                    val extracted = YouTubeMusicOfflineExtractor.extractOfflineLyricsForTrack(
                        context = ctx,
                        videoId = videoId ?: lastReportedId,
                        title = title,
                        artist = artist,
                        durationMs = duration
                    )
                    if (extracted != null && extracted.hasVocalLines) {
                        deliverExtractedOfflineLyrics(ctx, extracted)
                    }
                }
            }
        }
    }

    private fun maybeInspectCursorBlobAsync(blob: ByteArray, trackKey: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlobScanAtMs < 60L) return
        lastBlobScanAtMs = now

        val copy = blob.copyOf()
        val titleSnap = currentTitle
        val artistSnap = currentArtist
        val durSnap = currentDurationMs
        val vidSnap = lastReportedId
        val ctx = appContext ?: return

        executor.execute {
            if (currentTrackIdentity() != trackKey || lastDeliveredSyncedKey == trackKey) return@execute
            val parsed = if (copy[0] == '{'.code.toByte()) {
                runCatching {
                    YouTubeMusicInnertubeClient.parseBrowseLyricsResponse(
                        json = JSONObject(String(copy, Charsets.UTF_8)),
                        videoId = vidSnap,
                        title = titleSnap,
                        artist = artistSnap,
                        durationMs = durSnap
                    )
                }.getOrNull()
            } else {
                YouTubeProtobufLyricsScanner.extractFromBlob(
                    blob = copy,
                    videoId = vidSnap,
                    title = titleSnap,
                    artist = artistSnap,
                    durationMs = durSnap
                )
            }
            if (parsed != null && parsed.hasVocalLines && parsed.isSynced) {
                ModuleLogger.i(TAG, "Captured live synced offline lyrics from YTM SQLite cursor (${parsed.lines.size} lines)")
                deliverExtractedOfflineLyrics(ctx, parsed)
            }
        }
    }

    private fun maybeScheduleRecyclerHarvest(tv: TextView) {
        var parent = tv.parent as? ViewGroup
        var recyclerCandidate: ViewGroup? = null
        repeat(5) {
            val cur = parent ?: return@repeat
            val clsName = cur.javaClass.name
            if (clsName.contains("RecyclerView") || clsName.contains("Lyrics")) {
                recyclerCandidate = cur
                return@repeat
            }
            parent = cur.parent as? ViewGroup
        }
        val target = recyclerCandidate ?: return
        if (target.childCount < 5) return
        pendingRecyclerContainerRef = WeakReference(target)
        mainHandler.removeCallbacks(recyclerHarvestRunnable)
        mainHandler.postDelayed(recyclerHarvestRunnable, 750L)
    }

    private fun harvestLyricsFromRecyclerContainer(container: ViewGroup) {
        val ctx = appContext ?: return
        val title = currentTitle
        if (title.isBlank()) return
        val trackKey = currentTrackIdentity()
        if (lastDeliveredSyncedKey == trackKey || lastDeliveredStaticKey == trackKey) return

        val collected = ArrayList<String>(32)
        collectChildTextViews(container, collected, depth = 0)

        // Filter out UI headers/buttons; keep lyric lines
        val cleaned = collected
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    line.length in 1..140 &&
                    !line.equals(title, ignoreCase = true) &&
                    !line.equals(currentArtist, ignoreCase = true) &&
                    !line.contains("http://") &&
                    !line.contains("https://") &&
                    !line.equals("Lyrics", ignoreCase = true) &&
                    !line.equals("Şarkı Sözü", ignoreCase = true) &&
                    !line.equals("Şarkı Sözleri", ignoreCase = true) &&
                    !line.matches(Regex("""^\d{1,2}:\d{2}$"""))
            }

        val vocalLines = cleaned.filter { it.count(Char::isLetter) >= 3 }
        if (vocalLines.size < 6) return
        val avgLen = vocalLines.map { it.length }.average()
        if (avgLen !in 10.0..85.0) return

        val track = TrackLyrics(
            videoId = lastReportedId,
            title = title,
            artist = currentArtist,
            durationMs = currentDurationMs,
            lines = cleaned.mapIndexed { idx, line ->
                val interlude = line.all { it == '♪' || it == '♫' || it == '♬' || it.isWhitespace() }
                LyricLine(
                    text = if (interlude) "♪" else line,
                    startTimeMs = idx * 1000L,
                    endTimeMs = (idx + 1) * 1000L,
                    isInterlude = interlude
                )
            },
            isSynced = false,
            sourceMessage = "YouTube Music Offline"
        )
        ModuleLogger.i(TAG, "Captured offline lyrics from YTM Lyrics tab (${track.lines.size} lines)")
        executor.execute { deliverExtractedOfflineLyrics(ctx, track) }
    }

    private fun collectChildTextViews(group: ViewGroup, out: MutableList<String>, depth: Int) {
        if (depth > 4 || out.size > 160) return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE) continue
            when (child) {
                is TextView -> child.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                is ViewGroup -> collectChildTextViews(child, out, depth + 1)
            }
        }
    }

    private fun maybeCaptureDisplayedStaticLyrics(text: String) {
        val ctx = appContext ?: return
        val title = currentTitle
        if (title.isBlank()) return
        if (text.contains("http://") || text.contains("https://")) return
        val rawLines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rawLines.size < 6) return
        val avgLen = rawLines.map { it.length }.average()
        if (avgLen !in 8.0..90.0) return

        val trackKey = currentTrackIdentity()
        if (lastDeliveredSyncedKey == trackKey || lastDeliveredStaticKey == trackKey) return

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
        executor.execute { deliverExtractedOfflineLyrics(ctx, track) }
    }

    private fun deliverExtractedOfflineLyrics(ctx: Context, track: TrackLyrics) {
        val trackKey = "${track.title.lowercase()}|${track.artist.lowercase()}"
        if (track.isSynced) {
            lastDeliveredSyncedKey = trackKey
        } else {
            if (lastDeliveredSyncedKey == trackKey) return
            lastDeliveredStaticKey = trackKey
        }

        // 1. Persist & pin in HyperLyricsMM app's SQLite cache via ContentProvider
        LyricsBridgeClient.reportPinnedTrackLyrics(ctx, track)

        // 2. Direct SystemUI broadcast fallback (works immediately even if HyperOS blocks app cold-start)
        runCatching {
            val payload = json.encodeToString(track)
            val intent = Intent(ModuleConfig.ACTION_LYRICS_UPDATED).apply {
                putExtra(LyricsBridgeProvider.EXTRA_LYRICS_JSON, payload)
                putExtra(LyricsBridgeProvider.EXTRA_TITLE, track.title)
                putExtra(LyricsBridgeProvider.EXTRA_ARTIST, track.artist)
            }
            ctx.sendBroadcast(intent)
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
}
