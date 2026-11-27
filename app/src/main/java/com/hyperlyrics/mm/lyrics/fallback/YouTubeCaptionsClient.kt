package com.hyperlyrics.mm.lyrics.fallback

import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.lyrics.model.LyricLine
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.ytm.YouTubeMusicInnertubeClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

/**
 * Optional fallback that extracts millisecond-timed captions (human CC or YouTube ASR auto-captions)
 * directly from YouTube's `/youtubei/v1/player` `captionTracks` (`fmt=json3`).
 *
 * Especially useful for live sessions, acoustic covers, remixes, and indie tracks that lack
 * official YouTube Music lyrics yet have timed YouTube captions.
 */
class YouTubeCaptionsClient(
    private val httpClient: OkHttpClient = YouTubeMusicInnertubeClient.defaultHttpClient()
) {
    fun fetchTimedCaptions(
        videoId: String?,
        title: String,
        artist: String,
        durationMs: Long = 0L
    ): TrackLyrics? {
        if (!YouTubeMusicInnertubeClient.isValidVideoId(videoId) || videoId == null) return null

        val locale = Locale.getDefault()
        val hl = locale.language.ifBlank { "en" }
        val gl = locale.country.ifBlank { "US" }

        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "ANDROID_MUSIC")
                        .put("clientVersion", "7.29.52")
                        .put("hl", hl)
                        .put("gl", gl)
                        .put("androidSdkVersion", 34)
                )
            )
            .put("videoId", videoId)

        val playerReq = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", "com.google.android.apps.youtube.music/7.29.52 (Linux; U; Android 14)")
            .header("X-YouTube-Client-Name", "21")
            .header("X-YouTube-Client-Version", "7.29.52")
            .build()

        val captionTrackUrl = runCatching {
            httpClient.newCall(playerReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val text = resp.body?.string() ?: return@use null
                selectBestCaptionTrackUrl(JSONObject(text), hl)
            }
        }.onFailure {
            ModuleLogger.d(TAG, "Player captions query failed: ${it.message}")
        }.getOrNull() ?: return null

        val json3Url = if (captionTrackUrl.contains("fmt=")) {
            captionTrackUrl
        } else {
            val sep = if (captionTrackUrl.contains("?")) "&" else "?"
            "${captionTrackUrl}${sep}fmt=json3"
        }

        val capReq = Request.Builder()
            .url(json3Url)
            .header("User-Agent", "com.google.android.apps.youtube.music/7.29.52 (Linux; U; Android 14)")
            .build()

        return runCatching {
            httpClient.newCall(capReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string() ?: return@use null
                parseJson3Captions(
                    json = JSONObject(raw),
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    durationMs = durationMs
                )
            }
        }.onFailure {
            ModuleLogger.d(TAG, "Caption json3 fetch failed: ${it.message}")
        }.getOrNull()
    }

    companion object {
        private const val TAG = "YtCaptionsClient"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        fun selectBestCaptionTrackUrl(playerJson: JSONObject, preferredLang: String): String? {
            val tracks = playerJson.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks")
                ?: return null
            if (tracks.length() == 0) return null

            var bestUrl: String? = null
            var bestScore = Int.MIN_VALUE

            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                val baseUrl = track.optString("baseUrl", "").takeIf { it.isNotBlank() } ?: continue
                val lang = track.optString("languageCode", "")
                val kind = track.optString("kind", "")

                var score = 0
                // Prefer human-authored captions over ASR auto-generated captions
                if (kind != "asr") score += 20
                if (lang.equals(preferredLang, ignoreCase = true)) score += 15
                else if (lang.startsWith("en", ignoreCase = true)) score += 8

                if (score > bestScore) {
                    bestScore = score
                    bestUrl = baseUrl
                }
            }
            return bestUrl
        }

        fun parseJson3Captions(
            json: JSONObject,
            videoId: String?,
            title: String,
            artist: String,
            durationMs: Long
        ): TrackLyrics? {
            val events = json.optJSONArray("events") ?: return null
            val lines = mutableListOf<LyricLine>()

            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val startMs = ev.optLong("tStartMs", -1L)
                if (startMs < 0L) continue
                val durMs = ev.optLong("dDurationMs", 3_500L).coerceAtLeast(300L)
                val segs = ev.optJSONArray("segs") ?: continue

                val sb = StringBuilder()
                for (j in 0 until segs.length()) {
                    sb.append(segs.optJSONObject(j)?.optString("utf8", "").orEmpty())
                }
                val text = sb.toString()
                    .replace('\n', ' ')
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                if (text.isEmpty() || text == "[Music]" || text == "[Müzik]") continue

                val prev = lines.lastOrNull()
                if (prev != null && prev.text == text && startMs - prev.startTimeMs < 1500L) continue

                lines.add(
                    LyricLine(
                        text = text,
                        startTimeMs = startMs,
                        endTimeMs = startMs + durMs,
                        isInterlude = text.all { it == '♪' || it == '♫' || it.isWhitespace() }
                    )
                )
            }

            if (lines.size < 3) return null
            return TrackLyrics(
                videoId = videoId,
                title = title,
                artist = artist,
                durationMs = durationMs,
                lines = lines.sortedBy { it.startTimeMs },
                isSynced = true,
                sourceMessage = "YouTube Captions"
            )
        }
    }
}
