package com.hyperlyrics.mm.xposed.session

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.ipc.LyricsBridgeClient
import com.hyperlyrics.mm.ipc.LyricsBridgeProvider
import com.hyperlyrics.mm.lyrics.fallback.LrclibClient
import com.hyperlyrics.mm.lyrics.fallback.NeteaseLyricsClient
import com.hyperlyrics.mm.lyrics.fallback.YouTubeCaptionsClient
import com.hyperlyrics.mm.lyrics.model.ActiveLyricSnapshot
import com.hyperlyrics.mm.lyrics.model.LyricsState
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.sync.LyricsTimelineEngine
import com.hyperlyrics.mm.lyrics.ytm.YouTubeMusicInnertubeClient
import com.hyperlyrics.mm.xposed.RuntimeSettingsHolder
import kotlinx.serialization.json.Json
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks the active YouTube Music MediaSession inside SystemUI.
 *
 * Dual-path acquisition:
 *  - Watches `MediaSessionManager` active sessions for YouTube Music.
 *  - Also accepts direct `MediaSession.Token` + song/artist hints from `MiuiIslandMediaViewBinderImpl`
 *    so tracking works even if `Application.onCreate` ran before hooking or SystemUI permissions differ.
 *
 * Dual-path YouTube Music lyrics lookup:
 *  1. Calls our module app's `LyricsBridgeProvider` IPC (offline SQLite cache + YTM videoId + Innertube).
 *  2. If HyperOS Autostart/background restrictions block the ContentProvider from cold-starting,
 *     automatically falls back to querying YouTube Music Innertube directly with an in-memory LRU cache.
 */
object YouTubeMusicSessionTracker {
    private const val TAG = "YtmSessionTracker"

    data class TrackInfo(
        val packageName: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val videoId: String?
    ) {
        val identityKey: String
            get() = "$packageName|$title|$artist|$durationMs|${videoId.orEmpty()}"
    }

    interface Listener {
        fun onTrackChanged(track: TrackInfo?)
        fun onLyricsStateChanged(state: LyricsState)
        fun onPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, speed: Float)
    }

    private val started = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HyperLyricsMM-YtmFetch").apply { isDaemon = true }
    }
    private val fetchSequence = AtomicLong(0L)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val directInnertubeClient by lazy { YouTubeMusicInnertubeClient() }
    private val directLrclibClient by lazy { LrclibClient() }
    private val directCaptionsClient by lazy { YouTubeCaptionsClient() }
    private val directNeteaseClient by lazy { NeteaseLyricsClient() }
    private val broadcastJson = Json { ignoreUnknownKeys = true }

    private val memoryCache = object : LinkedHashMap<String, TrackLyrics>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackLyrics>?): Boolean =
            size > 48
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activeToken: MediaSession.Token? = null

    @Volatile
    private var activeController: MediaController? = null

    @Volatile
    private var activeCallback: MediaController.Callback? = null

    @Volatile
    var currentTrack: TrackInfo? = null
        private set

    @Volatile
    var currentLyricsState: LyricsState = LyricsState.Idle
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    private var anchorPositionMs: Long = 0L

    @Volatile
    private var anchorElapsedRealtimeMs: Long = 0L

    @Volatile
    private var playbackSpeed: Float = 1.0f

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            selectTargetMusicController(controllers.orEmpty())
        }

    fun start(app: Application) {
        if (!started.compareAndSet(false, true)) return
        appContext = app.applicationContext
        RuntimeSettingsHolder.attachSystemContext(app.applicationContext)
        registerLyricsUpdatedReceiver(app.applicationContext)

        runCatching {
            val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            if (manager == null) {
                ModuleLogger.w(TAG, "MediaSessionManager unavailable")
                return
            }
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, null, mainHandler)
            selectTargetMusicController(manager.getActiveSessions(null).orEmpty())
            RuntimeSettingsHolder.addListener {
                runCatching { selectTargetMusicController(manager.getActiveSessions(null).orEmpty()) }
            }
            ModuleLogger.i(TAG, "Music session tracker started (multi-app enabled)")
        }.onFailure { error ->
            started.set(false)
            ModuleLogger.e(TAG, "Failed to start MediaSession tracking", error)
        }
    }

    /**
     * Direct binding bridge invoked from HyperOS's Expanded Island `bindMediaData()`.
     * Guarantees YouTube Music tracking & playback clock sync even if MediaSessionManager
     * listener wasn't initialized yet or missed an already-playing session.
     */
    fun ensureBoundFromMediaData(
        context: Context,
        packageName: String,
        title: String?,
        artist: String?,
        token: MediaSession.Token?,
        playingHint: Boolean
    ) {
        if (!ModuleConfig.isTargetMusicPackage(packageName, RuntimeSettingsHolder.current)) return
        val appCtx = context.applicationContext ?: context
        if (appContext == null) {
            appContext = appCtx
            RuntimeSettingsHolder.attachSystemContext(appCtx)
        }
        registerLyricsUpdatedReceiver(appCtx)

        if (token != null && token != activeToken) {
            runCatching {
                val controller = MediaController(appCtx, token)
                attachController(controller, packageName)
            }.onFailure {
                ModuleLogger.w(TAG, "Direct MediaController bind from token failed", it)
            }
        }

        val cleanTitle = title?.trim().orEmpty()
        val cleanArtist = artist?.trim().orEmpty()
        if (cleanTitle.isNotEmpty()) {
            val existing = currentTrack
            if (existing == null ||
                !existing.title.equals(cleanTitle, ignoreCase = true) ||
                existing.packageName != packageName
            ) {
                val isYtm = ModuleConfig.isYouTubeMusicPackage(packageName)
                val fallbackTrack = TrackInfo(
                    packageName = packageName,
                    title = cleanTitle,
                    artist = cleanArtist,
                    album = "",
                    durationMs = activeController?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        ?.coerceAtLeast(0L) ?: 0L,
                    videoId = if (isYtm) activeController?.metadata?.let(::extractVideoIdFromMetadata) else null
                )
                currentTrack = fallbackTrack
                if (playingHint && !isPlaying) {
                    isPlaying = true
                    anchorElapsedRealtimeMs = SystemClock.elapsedRealtime()
                }
                notifyTrackChanged(fallbackTrack)
                requestLyricsFetch(fallbackTrack, forceRefresh = false)
            }
        }
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        mainHandler.post {
            runCatching {
                listener.onTrackChanged(currentTrack)
                listener.onLyricsStateChanged(currentLyricsState)
                listener.onPlaybackStateChanged(isPlaying, currentPositionMs(), playbackSpeed)
            }
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun currentPositionMs(): Long {
        val base = anchorPositionMs
        if (!isPlaying) return base.coerceAtLeast(0L)
        val delta = (SystemClock.elapsedRealtime() - anchorElapsedRealtimeMs).coerceAtLeast(0L)
        val computed = base + (delta * playbackSpeed).toLong()
        val duration = currentTrack?.durationMs ?: 0L
        return if (duration > 0L) computed.coerceIn(0L, duration) else computed.coerceAtLeast(0L)
    }

    fun currentActiveLyric(): ActiveLyricSnapshot {
        val ready = currentLyricsState as? LyricsState.Ready ?: return ActiveLyricSnapshot.EMPTY
        val settings = RuntimeSettingsHolder.current
        return LyricsTimelineEngine.resolve(
            lyrics = ready.lyrics,
            rawPositionMs = currentPositionMs(),
            offsetMs = settings.syncOffsetMs,
            durationMs = currentTrack?.durationMs ?: ready.lyrics.durationMs
        )
    }

    fun seekTo(positionMs: Long): Boolean {
        val controller = activeController ?: return false
        return runCatching {
            val clamped = positionMs.coerceAtLeast(0L)
            controller.transportControls.seekTo(clamped)
            anchorPositionMs = clamped
            anchorElapsedRealtimeMs = SystemClock.elapsedRealtime()
            notifyPlaybackChanged()
            true
        }.getOrDefault(false)
    }

    fun togglePlayPause(): Boolean {
        val controller = activeController ?: return false
        return runCatching {
            if (isPlaying) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
            true
        }.getOrDefault(false)
    }

    fun skipToPrevious(): Boolean {
        val controller = activeController ?: return false
        return runCatching {
            controller.transportControls.skipToPrevious()
            true
        }.getOrDefault(false)
    }

    fun skipToNext(): Boolean {
        val controller = activeController ?: return false
        return runCatching {
            controller.transportControls.skipToNext()
            true
        }.getOrDefault(false)
    }

    fun currentArtworkBitmap(): Bitmap? {
        val metadata = activeController?.metadata ?: return null
        return runCatching {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }.getOrNull()
    }

    private var lyricsReceiverRegistered = false

    private fun registerLyricsUpdatedReceiver(ctx: Context) {
        if (lyricsReceiverRegistered) return
        lyricsReceiverRegistered = true
        runCatching {
            ctx.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val track = currentTrack
                        val directJson = intent?.getStringExtra(LyricsBridgeProvider.EXTRA_LYRICS_JSON)
                        if (!directJson.isNullOrBlank()) {
                            val decoded = runCatching {
                                broadcastJson.decodeFromString<TrackLyrics>(directJson)
                            }.getOrNull()
                            if (decoded != null && decoded.hasVocalLines) {
                                val decodedKey = "${decoded.title.lowercase()}|${decoded.artist.lowercase()}"
                                synchronized(memoryCache) {
                                    val existing = memoryCache[decodedKey]
                                    if (existing == null || !existing.isSynced || decoded.isSynced) {
                                        memoryCache[decodedKey] = decoded
                                    }
                                }
                                if (track != null &&
                                    (decoded.title.equals(track.title, ignoreCase = true) ||
                                        (track.videoId != null && track.videoId == decoded.videoId))
                                ) {
                                    val curReady = currentLyricsState as? LyricsState.Ready
                                    if (curReady == null || !curReady.lyrics.isSynced || decoded.isSynced) {
                                        val ready = LyricsState.Ready(decoded)
                                        currentLyricsState = ready
                                        notifyLyricsChanged(ready)
                                        return
                                    }
                                }
                            }
                        }

                        if (track == null) return
                        // Clear in-memory entry and reload fresh pinned/offline/speech lyrics
                        val cacheKey = "${track.title.lowercase()}|${track.artist.lowercase()}"
                        synchronized(memoryCache) { memoryCache.remove(cacheKey) }
                        requestLyricsFetch(track, forceRefresh = false)
                    }
                },
                IntentFilter(ModuleConfig.ACTION_LYRICS_UPDATED),
                Context.RECEIVER_EXPORTED
            )
        }
    }

    fun forceRetryCurrentTrack() {
        val track = currentTrack ?: return
        requestLyricsFetch(track, forceRefresh = true)
    }

    private fun selectTargetMusicController(controllers: List<MediaController>) {
        val settings = RuntimeSettingsHolder.current
        val targetController = controllers.firstOrNull { controller ->
            ModuleConfig.isTargetMusicPackage(controller.packageName, settings) &&
                controller.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull { controller ->
            ModuleConfig.isTargetMusicPackage(controller.packageName, settings)
        }

        if (targetController?.sessionToken == activeToken) return

        if (targetController == null) {
            detachController()
            fetchSequence.incrementAndGet()
            currentTrack = null
            isPlaying = false
            anchorPositionMs = 0L
            currentLyricsState = LyricsState.Idle
            notifyTrackChanged(null)
            notifyLyricsChanged(LyricsState.Idle)
            notifyPlaybackChanged()
            return
        }

        attachController(targetController, targetController.packageName)
    }

    private fun attachController(controller: MediaController, packageName: String) {
        detachController()
        activeController = controller
        activeToken = controller.sessionToken

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                if (controller.sessionToken != activeToken) return
                handleMetadata(packageName, metadata)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (controller.sessionToken != activeToken) return
                handlePlaybackState(state)
            }

            override fun onSessionDestroyed() {
                if (controller.sessionToken == activeToken) {
                    selectTargetMusicController(emptyList())
                }
            }
        }
        activeCallback = callback
        controller.registerCallback(callback, mainHandler)
        handlePlaybackState(controller.playbackState)
        handleMetadata(packageName, controller.metadata)
    }

    private fun detachController() {
        activeController?.let { old -> activeCallback?.let { runCatching { old.unregisterCallback(it) } } }
        activeController = null
        activeToken = null
        activeCallback = null
    }

    private fun handleMetadata(packageName: String, metadata: MediaMetadata?) {
        if (metadata == null) return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = (metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
            ?.trim().orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)

        if (title.isEmpty()) return

        val videoId = if (ModuleConfig.isYouTubeMusicPackage(packageName)) {
            extractVideoIdFromMetadata(metadata)
        } else {
            null
        }
        val incoming = TrackInfo(
            packageName = packageName,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            videoId = videoId
        )

        val previous = currentTrack
        if (previous != null &&
            previous.title == incoming.title &&
            previous.artist == incoming.artist &&
            (incoming.videoId == null || incoming.videoId == previous.videoId)
        ) {
            if (previous.durationMs == 0L && incoming.durationMs > 0L) {
                currentTrack = incoming
                notifyTrackChanged(incoming)
            }
            return
        }

        currentTrack = incoming
        notifyTrackChanged(incoming)
        requestLyricsFetch(incoming, forceRefresh = false)
    }

    private fun handlePlaybackState(state: PlaybackState?) {
        if (state == null) {
            isPlaying = false
            notifyPlaybackChanged()
            return
        }
        val playingNow = state.state == PlaybackState.STATE_PLAYING
        val speedNow = state.playbackSpeed.takeIf { it > 0.1f } ?: 1.0f
        isPlaying = playingNow
        playbackSpeed = speedNow
        anchorPositionMs = state.position.coerceAtLeast(0L)
        anchorElapsedRealtimeMs = state.lastPositionUpdateTime.takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()
        notifyPlaybackChanged()
    }

    private fun requestLyricsFetch(track: TrackInfo, forceRefresh: Boolean) {
        val ctx = appContext
        val cacheKey = "${track.title.lowercase()}|${track.artist.lowercase()}"

        if (!forceRefresh) {
            synchronized(memoryCache) { memoryCache[cacheKey] }?.let { cached ->
                val ready = LyricsState.Ready(cached)
                currentLyricsState = ready
                notifyLyricsChanged(ready)
                return
            }
        }

        val seq = fetchSequence.incrementAndGet()
        val loadingState = LyricsState.Loading(track.title, track.artist)
        currentLyricsState = loadingState
        notifyLyricsChanged(loadingState)

        fetchExecutor.execute {
            if (fetchSequence.get() != seq) return@execute

            // Tier 1: Module app IPC bridge (SQLite offline cache + YTM hook videoId + LRCLIB/Innertube)
            var fetched: TrackLyrics? = if (ctx != null) {
                LyricsBridgeClient.fetchLyrics(
                    context = ctx,
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    forceRefresh = forceRefresh,
                    sourcePackage = track.packageName
                )
            } else {
                null
            }

            // Tier 2: Direct SystemUI fallback (resilient if HyperOS blocks IPC)
            if ((fetched == null || !fetched.hasVocalLines) && fetchSequence.get() == seq) {
                fetched = runCatching {
                    val settings = RuntimeSettingsHolder.current
                    val isNonYtm = !ModuleConfig.isYouTubeMusicPackage(track.packageName)
                    var direct: TrackLyrics? = null

                    if (isNonYtm && settings.fallbackLrclibEnabled) {
                        val lrc = directLrclibClient.fetchLyrics(
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            durationMs = track.durationMs,
                            videoId = null
                        )
                        if (lrc != null && lrc.hasVocalLines && lrc.isSynced) {
                            direct = lrc
                        }
                    }

                    if (direct == null || !direct.hasVocalLines) {
                        val byId = track.videoId?.let { vid ->
                            directInnertubeClient.fetchLyricsByVideoId(
                                videoId = vid,
                                title = track.title,
                                artist = track.artist,
                                durationMs = track.durationMs
                            )
                        }
                        direct = if (byId != null && byId.hasVocalLines) {
                            byId
                        } else {
                            directInnertubeClient.searchAndFetchLyrics(
                                title = track.title,
                                artist = track.artist,
                                album = track.album,
                                durationMs = track.durationMs
                            )
                        }
                    }

                    if ((direct == null || !direct.hasVocalLines) && (settings.externalFallbackEnabled || isNonYtm)) {
                        if (!isNonYtm && settings.fallbackLrclibEnabled) {
                            direct = directLrclibClient.fetchLyrics(
                                title = track.title,
                                artist = track.artist,
                                album = track.album,
                                durationMs = track.durationMs,
                                videoId = track.videoId
                            )
                        }
                        if ((direct == null || !direct.hasVocalLines) && settings.fallbackYtCaptionsEnabled) {
                            val capId = track.videoId ?: directInnertubeClient.findTopVideoId("${track.title} ${track.artist}")
                            direct = directCaptionsClient.fetchTimedCaptions(
                                videoId = capId,
                                title = track.title,
                                artist = track.artist,
                                durationMs = track.durationMs
                            )
                        }
                        if ((direct == null || !direct.hasVocalLines) && settings.fallbackNeteaseEnabled) {
                            direct = directNeteaseClient.fetchLyrics(
                                title = track.title,
                                artist = track.artist,
                                durationMs = track.durationMs,
                                videoId = track.videoId
                            )
                        }
                    }
                    direct
                }.onFailure { err ->
                    ModuleLogger.w(TAG, "Direct SystemUI lyrics fallback failed", err)
                }.getOrNull()
            }

            if (fetchSequence.get() != seq) return@execute

            val nextState = if (fetched != null && fetched.hasVocalLines) {
                synchronized(memoryCache) { memoryCache[cacheKey] = fetched }
                LyricsState.Ready(fetched)
            } else {
                LyricsState.Unavailable(track.title, track.artist, "no_lyrics")
            }
            currentLyricsState = nextState
            mainHandler.post {
                if (fetchSequence.get() == seq) {
                    notifyLyricsChanged(nextState)
                }
            }
        }
    }

    private fun extractVideoIdFromMetadata(metadata: MediaMetadata): String? {
        val directId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        if (YouTubeMusicInnertubeClient.isValidVideoId(directId)) return directId?.trim()

        val uriCandidates = listOf(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        )
        for (uri in uriCandidates) {
            YouTubeMusicInnertubeClient.extractVideoIdFromUri(uri)?.let { return it }
        }
        return null
    }

    private fun notifyTrackChanged(track: TrackInfo?) {
        mainHandler.post {
            listeners.forEach { runCatching { it.onTrackChanged(track) } }
        }
    }

    private fun notifyLyricsChanged(state: LyricsState) {
        mainHandler.post {
            listeners.forEach { runCatching { it.onLyricsStateChanged(state) } }
        }
    }

    private fun notifyPlaybackChanged() {
        val playing = isPlaying
        val pos = currentPositionMs()
        val speed = playbackSpeed
        mainHandler.post {
            listeners.forEach { runCatching { it.onPlaybackStateChanged(playing, pos, speed) } }
        }
    }
}
