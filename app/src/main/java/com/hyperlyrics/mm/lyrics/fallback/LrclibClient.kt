package com.hyperlyrics.mm.lyrics.fallback

import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.parser.LrcParser
import com.hyperlyrics.mm.lyrics.ytm.YouTubeMusicInnertubeClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Optional external synced lyrics fallback using LRCLIB (https://lrclib.net).
 * Only invoked when YouTube Music itself has no lyrics AND the user enabled external fallback.
 */
class LrclibClient(
    private val httpClient: OkHttpClient = YouTubeMusicInnertubeClient.defaultHttpClient()
) {
    fun fetchLyrics(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L,
        videoId: String? = null
    ): TrackLyrics? {
        val cleanTitle = YouTubeMusicInnertubeClient.cleanTitleForSearch(title)
        val cleanArtist = YouTubeMusicInnertubeClient.cleanArtistName(artist)
        if (cleanTitle.isBlank()) return null

        // 1. Exact lookup when artist & duration are available
        if (cleanArtist.isNotBlank() && durationMs > 0L) {
            fetchExact(cleanTitle, cleanArtist, album.trim(), (durationMs / 1000L).toInt(), videoId)
                ?.let { return it }
        }

        // 2. Ranked search fallback
        return fetchSearch(cleanTitle, cleanArtist, durationMs, videoId)
    }

    private fun fetchExact(
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
        videoId: String?
    ): TrackLyrics? {
        val urlBuilder = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("duration", durationSec.toString())
        if (album.isNotBlank()) {
            urlBuilder.addQueryParameter("album_name", album)
        }

        val req = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                parseLrclibObject(JSONObject(body), title, artist, durationSec * 1000L, videoId)
            }
        }.onFailure {
            ModuleLogger.d(TAG, "LRCLIB /get failed: ${it.message}")
        }.getOrNull()
    }

    private fun fetchSearch(
        title: String,
        artist: String,
        durationMs: Long,
        videoId: String?
    ): TrackLyrics? {
        val urlBuilder = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
        if (artist.isNotBlank()) {
            urlBuilder.addQueryParameter("track_name", title)
            urlBuilder.addQueryParameter("artist_name", artist)
        } else {
            urlBuilder.addQueryParameter("q", title)
        }

        val req = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val array = JSONArray(body)
                if (array.length() == 0) return@use null

                var bestSynced: Pair<Double, TrackLyrics>? = null
                var bestPlain: Pair<Double, TrackLyrics>? = null

                for (i in 0 until minOf(array.length(), 10)) {
                    val obj = array.optJSONObject(i) ?: continue
                    val candTitle = obj.optString("trackName", title)
                    val candArtist = obj.optString("artistName", artist)
                    val candDurationMs = (obj.optDouble("duration", 0.0) * 1000.0).toLong()

                    val parsed = parseLrclibObject(
                        obj = obj,
                        fallbackTitle = if (artist.isBlank()) candTitle else title,
                        fallbackArtist = if (artist.isBlank()) candArtist else artist,
                        durationMs = durationMs.takeIf { it > 0L } ?: candDurationMs,
                        videoId = videoId
                    ) ?: continue

                    var score = 10.0 - i * 0.5
                    if (durationMs > 0L && candDurationMs > 0L) {
                        val diff = abs(durationMs - candDurationMs)
                        score += when {
                            diff <= 3_000L -> 25.0
                            diff <= 8_000L -> 12.0
                            diff <= 20_000L -> 2.0
                            else -> -15.0
                        }
                    }

                    if (parsed.isSynced) {
                        if (bestSynced == null || score > bestSynced.first) {
                            bestSynced = score to parsed
                        }
                    } else {
                        if (bestPlain == null || score > bestPlain.first) {
                            bestPlain = score to parsed
                        }
                    }
                }

                bestSynced?.second ?: bestPlain?.second
            }
        }.onFailure {
            ModuleLogger.d(TAG, "LRCLIB /search failed: ${it.message}")
        }.getOrNull()
    }

    private fun parseLrclibObject(
        obj: JSONObject,
        fallbackTitle: String,
        fallbackArtist: String,
        durationMs: Long,
        videoId: String?
    ): TrackLyrics? {
        if (obj.optBoolean("instrumental", false)) return null
        val synced = obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val plain = obj.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" }

        if (synced != null) {
            val parsed = LrcParser.parseLrc(
                lrcText = synced,
                title = fallbackTitle,
                artist = fallbackArtist,
                videoId = videoId,
                durationMs = durationMs,
                sourceMessage = "LRCLIB"
            )
            if (parsed != null && parsed.hasVocalLines) return parsed
        }

        if (plain != null) {
            val parsed = LrcParser.parseAuto(
                content = plain,
                title = fallbackTitle,
                artist = fallbackArtist,
                videoId = videoId,
                durationMs = durationMs,
                sourceMessage = "LRCLIB"
            )
            if (parsed != null && parsed.hasVocalLines) return parsed
        }
        return null
    }

    companion object {
        private const val TAG = "LrclibClient"
        private const val USER_AGENT = "HyperLyricsMM/1.2.1 (https://github.com/ParongUs/HyperLyricsMM)"
    }
}
