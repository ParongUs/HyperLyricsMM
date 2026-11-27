package com.hyperlyrics.mm.lyrics.fallback

import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.parser.LrcParser
import com.hyperlyrics.mm.lyrics.ytm.YouTubeMusicInnertubeClient
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs

/**
 * Optional NetEase Cloud Music synced lyrics fallback.
 */
class NeteaseLyricsClient(
    private val httpClient: OkHttpClient = YouTubeMusicInnertubeClient.defaultHttpClient()
) {
    fun fetchLyrics(
        title: String,
        artist: String,
        durationMs: Long = 0L,
        videoId: String? = null
    ): TrackLyrics? {
        val cleanTitle = YouTubeMusicInnertubeClient.cleanTitleForSearch(title)
        val cleanArtist = YouTubeMusicInnertubeClient.cleanArtistName(artist)
        if (cleanTitle.isBlank()) return null

        val query = if (cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else cleanTitle
        val songId = searchBestSongId(query, durationMs) ?: return null
        return fetchLyricById(songId, title, artist, durationMs, videoId)
    }

    private fun searchBestSongId(query: String, expectedDurationMs: Long): Long? {
        val form = FormBody.Builder()
            .add("s", query)
            .add("type", "1")
            .add("limit", "6")
            .add("offset", "0")
            .build()

        val req = Request.Builder()
            .url("https://music.163.com/api/search/get")
            .post(form)
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string() ?: return@use null
                val songs = JSONObject(raw).optJSONObject("result")?.optJSONArray("songs")
                    ?: return@use null
                if (songs.length() == 0) return@use null

                var bestId: Long? = null
                var bestScore = Double.NEGATIVE_INFINITY
                for (i in 0 until songs.length()) {
                    val song = songs.optJSONObject(i) ?: continue
                    val id = song.optLong("id", -1L)
                    if (id <= 0L) continue
                    val dur = song.optLong("duration", 0L)
                    var score = 10.0 - i * 1.5
                    if (expectedDurationMs > 0L && dur > 0L) {
                        val diff = abs(expectedDurationMs - dur)
                        score += when {
                            diff <= 3_000L -> 20.0
                            diff <= 8_000L -> 8.0
                            else -> -10.0
                        }
                    }
                    if (score > bestScore) {
                        bestScore = score
                        bestId = id
                    }
                }
                bestId
            }
        }.onFailure {
            ModuleLogger.d(TAG, "NetEase search failed: ${it.message}")
        }.getOrNull()
    }

    private fun fetchLyricById(
        songId: Long,
        title: String,
        artist: String,
        durationMs: Long,
        videoId: String?
    ): TrackLyrics? {
        val req = Request.Builder()
            .url("https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=1&kv=1&tv=-1")
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string() ?: return@use null
                val lrc = JSONObject(raw).optJSONObject("lrc")?.optString("lyric", "")
                    ?.takeIf { it.isNotBlank() } ?: return@use null
                LrcParser.parseAuto(
                    content = lrc,
                    title = title,
                    artist = artist,
                    videoId = videoId,
                    durationMs = durationMs,
                    sourceMessage = "NetEase"
                )?.takeIf { it.hasVocalLines }
            }
        }.onFailure {
            ModuleLogger.d(TAG, "NetEase lyric fetch failed: ${it.message}")
        }.getOrNull()
    }

    companion object {
        private const val TAG = "NeteaseClient"
    }
}
