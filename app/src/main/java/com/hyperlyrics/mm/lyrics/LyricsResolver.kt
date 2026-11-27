package com.hyperlyrics.mm.lyrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.lyrics.cache.LyricsCacheDatabase
import com.hyperlyrics.mm.lyrics.fallback.LrclibClient
import com.hyperlyrics.mm.lyrics.fallback.NeteaseLyricsClient
import com.hyperlyrics.mm.lyrics.fallback.YouTubeCaptionsClient
import com.hyperlyrics.mm.lyrics.local.LocalLyricsStore
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.ytm.YouTubeMusicInnertubeClient

/**
 * Unified lookup chain:
 *  1. Offline / Pinned SQLite cache (instant offline playback for Premium downloads & imports)
 *  2. Local `.lrc` / `.ttml` / `.txt` directory (offline)
 *  3. Native YouTube Music Innertube API (online)
 *  4. Optional opt-in external providers (LRCLIB, YouTube Captions, NetEase) when YTM has no lyrics
 */
class LyricsResolver(
    private val context: Context,
    private val cacheDb: LyricsCacheDatabase,
    private val ytmClient: YouTubeMusicInnertubeClient = YouTubeMusicInnertubeClient(),
    private val lrclibClient: LrclibClient = LrclibClient(),
    private val captionsClient: YouTubeCaptionsClient = YouTubeCaptionsClient(),
    private val neteaseClient: NeteaseLyricsClient = NeteaseLyricsClient()
) {
    fun resolve(
        videoId: String?,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L,
        settings: ModuleSettings,
        forceRefresh: Boolean = false,
        pinIfFound: Boolean = false,
        sourcePackage: String? = null
    ): TrackLyrics? {
        if (!forceRefresh) {
            val cached = cacheDb.get(videoId, title, artist)
            if (cached != null && cached.hasVocalLines) {
                if (pinIfFound && !cached.videoId.isNullOrBlank()) {
                    cacheDb.markPinnedByVideoId(cached.videoId)
                }
                return cached
            }
        }

        if (settings.localLrcFolderEnabled) {
            val local = LocalLyricsStore.findLocalLyrics(
                context = context,
                treeUriString = settings.localLrcTreeUri,
                videoId = videoId,
                title = title,
                artist = artist,
                durationMs = durationMs
            )
            if (local != null && local.hasVocalLines) {
                cacheDb.put(local, pinned = true)
                return local
            }
        }

        if (!hasInternet()) {
            ModuleLogger.d(TAG, "Device offline; cache/local miss for '$title'")
            return null
        }

        val isNonYtmApp = !sourcePackage.isNullOrBlank() && !ModuleConfig.isYouTubeMusicPackage(sourcePackage)
        var staticBackup: TrackLyrics? = null

        // For Spotify, Deezer, Apple Music, Tidal, local players, etc., LRCLIB matches album track
        // durations with millisecond accuracy — try synced LRCLIB first before falling back to YTM search.
        if (isNonYtmApp && settings.fallbackLrclibEnabled) {
            val lrc = lrclibClient.fetchLyrics(title, artist, album, durationMs, null)
            if (lrc != null && lrc.hasVocalLines) {
                if (lrc.isSynced) {
                    cacheDb.put(lrc, pinned = pinIfFound)
                    return lrc
                }
                staticBackup = lrc
            }
        }

        var resolvedId = videoId?.takeIf { YouTubeMusicInnertubeClient.isValidVideoId(it) }
        var nativeResult: TrackLyrics? = null
        if (resolvedId != null) {
            nativeResult = ytmClient.fetchLyricsByVideoId(resolvedId, title, artist, durationMs)
        }
        if (nativeResult == null || !nativeResult.hasVocalLines) {
            nativeResult = ytmClient.searchAndFetchLyrics(title, artist, album, durationMs)
            if (resolvedId == null) resolvedId = nativeResult?.videoId
        }
        if (nativeResult != null && nativeResult.hasVocalLines) {
            cacheDb.put(nativeResult, pinned = pinIfFound)
            return nativeResult
        }

        if (!settings.externalFallbackEnabled && !isNonYtmApp) {
            return staticBackup?.also { cacheDb.put(it, pinned = pinIfFound) }
        }

        if (!isNonYtmApp && settings.fallbackLrclibEnabled) {
            val lrc = lrclibClient.fetchLyrics(title, artist, album, durationMs, resolvedId)
            if (lrc != null && lrc.hasVocalLines) {
                if (lrc.isSynced) {
                    cacheDb.put(lrc, pinned = pinIfFound)
                    return lrc
                }
                staticBackup = lrc
            }
        }

        if (settings.fallbackYtCaptionsEnabled) {
            val capId = resolvedId ?: findVideoIdOnly(title, artist)
            if (capId != null) {
                val cap = captionsClient.fetchTimedCaptions(capId, title, artist, durationMs)
                if (cap != null && cap.hasVocalLines) {
                    cacheDb.put(cap, pinned = pinIfFound)
                    return cap
                }
            }
        }

        if (settings.fallbackNeteaseEnabled) {
            val net = neteaseClient.fetchLyrics(title, artist, durationMs, resolvedId)
            if (net != null && net.hasVocalLines) {
                if (net.isSynced) {
                    cacheDb.put(net, pinned = pinIfFound)
                    return net
                }
                if (staticBackup == null) staticBackup = net
            }
        }

        if (staticBackup != null) {
            cacheDb.put(staticBackup, pinned = pinIfFound)
            return staticBackup
        }
        return null
    }

    private fun findVideoIdOnly(title: String, artist: String): String? {
        val cleanT = YouTubeMusicInnertubeClient.cleanTitleForSearch(title)
        val cleanA = YouTubeMusicInnertubeClient.cleanArtistName(artist)
        val query = if (cleanA.isNotEmpty()) "$cleanT $cleanA" else cleanT
        if (query.isBlank()) return null
        return runCatching { ytmClient.findTopVideoId(query) }.getOrNull()
    }

    private fun hasInternet(): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(true)
    }

    companion object {
        private const val TAG = "LyricsResolver"
    }
}
