package com.hyperlyrics.mm.lyrics.ytm

import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.lyrics.model.LyricLine
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Direct YouTube Music Innertube API client (ANDROID_MUSIC client).
 *
 * Pipeline:
 *  1. If exact [videoId] is known (from YTM hook or MediaMetadata), skip search.
 *  2. Otherwise query YouTube Music /youtubei/v1/search (Songs filter first, then broad)
 *     parsing ANDROID_MUSIC `musicTwoColumnItemRenderer`, `musicResponsiveListItemRenderer`,
 *     and raw videoId fallback, ranking candidates by title, artist, and duration.
 *  3. Query /youtubei/v1/next to obtain YouTube Music's official lyrics tab browseId (MPLYt_ / MPLYd_).
 *  4. Query /youtubei/v1/browse to fetch exact YouTube Music timedLyricsData (millisecond cues)
 *     or static lyrics fallback.
 */
class YouTubeMusicInnertubeClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    data class SearchCandidate(
        val videoId: String,
        val title: String,
        val subtitle: String,
        val parsedArtist: String,
        val durationMs: Long,
        val score: Double
    )

    fun fetchLyricsByVideoId(
        videoId: String,
        title: String = "",
        artist: String = "",
        durationMs: Long = 0L
    ): TrackLyrics? {
        if (!isValidVideoId(videoId)) return null
        val browseId = fetchLyricsBrowseId(videoId) ?: return null
        val browseJson = postInnertube(
            endpoint = "browse",
            payload = JSONObject().put("browseId", browseId)
        ) ?: return null

        return parseBrowseLyricsResponse(
            json = browseJson,
            videoId = videoId,
            title = title,
            artist = artist,
            durationMs = durationMs
        )
    }

    fun searchAndFetchLyrics(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ): TrackLyrics? {
        val rawTitle = title.trim()
        val rawArtist = cleanArtistName(artist)
        if (rawTitle.isEmpty()) return null

        val cleanedTitle = cleanTitleForSearch(rawTitle)
        val query = buildString {
            append(cleanedTitle.ifEmpty { rawTitle })
            if (rawArtist.isNotEmpty()) {
                append(' ')
                append(rawArtist)
            }
        }

        // Phase 1: Songs-only filter on YouTube Music
        val songCandidates = searchCandidates(
            query = query,
            filterParams = SONGS_SEARCH_PARAMS,
            expectedTitle = cleanedTitle.ifEmpty { rawTitle },
            expectedArtist = rawArtist,
            expectedAlbum = album,
            expectedDurationMs = durationMs
        )

        var staticFallback: TrackLyrics? = null
        val triedVideoIds = HashSet<String>()

        for (candidate in songCandidates.take(4)) {
            if (!triedVideoIds.add(candidate.videoId)) continue
            val resolvedTitle = if (rawArtist.isEmpty() && candidate.title.isNotBlank()) {
                candidate.title
            } else {
                rawTitle.ifEmpty { candidate.title }
            }
            val resolvedArtist = rawArtist.ifEmpty { candidate.parsedArtist.ifEmpty { candidate.subtitle } }
            val result = fetchLyricsByVideoId(
                videoId = candidate.videoId,
                title = resolvedTitle,
                artist = resolvedArtist,
                durationMs = durationMs.takeIf { it > 0L } ?: candidate.durationMs
            )
            if (result != null && result.hasVocalLines) {
                if (result.isSynced) return result
                if (staticFallback == null) staticFallback = result
            }
        }

        // Phase 2: Unfiltered search (covers official audio/MVs/covers not under Songs tab)
        val broadCandidates = searchCandidates(
            query = query,
            filterParams = null,
            expectedTitle = cleanedTitle.ifEmpty { rawTitle },
            expectedArtist = rawArtist,
            expectedAlbum = album,
            expectedDurationMs = durationMs
        ).filter { it.videoId !in triedVideoIds }

        for (candidate in broadCandidates.take(3)) {
            if (!triedVideoIds.add(candidate.videoId)) continue
            val resolvedTitle = if (rawArtist.isEmpty() && candidate.title.isNotBlank()) {
                candidate.title
            } else {
                rawTitle.ifEmpty { candidate.title }
            }
            val resolvedArtist = rawArtist.ifEmpty { candidate.parsedArtist.ifEmpty { candidate.subtitle } }
            val result = fetchLyricsByVideoId(
                videoId = candidate.videoId,
                title = resolvedTitle,
                artist = resolvedArtist,
                durationMs = durationMs.takeIf { it > 0L } ?: candidate.durationMs
            )
            if (result != null && result.hasVocalLines) {
                if (result.isSynced) return result
                if (staticFallback == null) staticFallback = result
            }
        }

        return staticFallback
    }

    fun fetchLyricsBrowseId(videoId: String): String? {
        val payload = JSONObject()
            .put("videoId", videoId)
            .put("isAudioOnly", true)
        val nextJson = postInnertube("next", payload) ?: return null
        return extractLyricsBrowseId(nextJson)
    }

    fun findTopVideoId(query: String): String? {
        val clean = query.trim()
        if (clean.isEmpty()) return null
        return searchCandidates(clean, null, clean, "", "", 0L).firstOrNull()?.videoId
    }

    private fun searchCandidates(
        query: String,
        filterParams: String?,
        expectedTitle: String,
        expectedArtist: String,
        expectedAlbum: String,
        expectedDurationMs: Long
    ): List<SearchCandidate> {
        val payload = JSONObject().put("query", query)
        if (!filterParams.isNullOrEmpty()) {
            payload.put("params", filterParams)
        }
        val searchJson = postInnertube("search", payload) ?: return emptyList()
        return parseSearchCandidates(
            json = searchJson,
            expectedTitle = expectedTitle,
            expectedArtist = expectedArtist,
            expectedAlbum = expectedAlbum,
            expectedDurationMs = expectedDurationMs
        )
    }

    private fun postInnertube(endpoint: String, payload: JSONObject): JSONObject? {
        val locale = Locale.getDefault()
        val hl = locale.language.ifBlank { "en" }
        val gl = locale.country.ifBlank { "US" }

        val clientObj = JSONObject()
            .put("clientName", CLIENT_NAME)
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", hl)
            .put("gl", gl)
            .put("androidSdkVersion", 34)

        val rootBody = JSONObject(payload.toString())
            .put("context", JSONObject().put("client", clientObj))

        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(rootBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", "com.google.android.apps.youtube.music/$CLIENT_VERSION (Linux; U; Android 14)")
            .header("X-YouTube-Client-Name", CLIENT_ID)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("Origin", "https://music.youtube.com")
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ModuleLogger.w(TAG, "Innertube /$endpoint HTTP ${response.code}")
                    return@use null
                }
                val text = response.body?.string() ?: return@use null
                JSONObject(text)
            }
        }.onFailure { error ->
            ModuleLogger.w(TAG, "Innertube /$endpoint failed", error)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "YtmInnertube"
        private const val CLIENT_NAME = "ANDROID_MUSIC"
        private const val CLIENT_ID = "21"
        private const val CLIENT_VERSION = "7.29.52"
        private const val SONGS_SEARCH_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
        private val DURATION_REGEX = Regex("""\b(\d{1,2}):(\d{2})(?::(\d{2}))?\b""")
        private val NOISE_TITLE_REGEX = Regex(
            """\s*[\(\[](?:official\s*(?:music\s*)?video|official\s*audio|lyric\s*video|lyrics|visualizer|hd|4k)[\)\]]""",
            RegexOption.IGNORE_CASE
        )

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun isValidVideoId(value: String?): Boolean =
            !value.isNullOrBlank() && VIDEO_ID_REGEX.matches(value.trim())

        fun cleanArtistName(artist: String): String {
            return artist.trim()
                .removeSuffix(" - Topic")
                .removeSuffix(" - Konu")
                .trim()
        }

        fun cleanTitleForSearch(title: String): String {
            return title.replace(NOISE_TITLE_REGEX, "").trim()
        }

        fun extractVideoIdFromUri(uri: String?): String? {
            if (uri.isNullOrBlank()) return null
            val viMatch = Regex("""/vi(?:_webp)?/([A-Za-z0-9_-]{11})/""").find(uri)
            if (viMatch != null) return viMatch.groupValues[1]
            val vMatch = Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(uri)
            if (vMatch != null) return vMatch.groupValues[1]
            return null
        }

        /**
         * Recursively searches a /next JSON response for YouTube Music's lyrics tab browseId.
         * Prefers timed lyrics (MPLYt_...) over static lyrics (MPLYd_...).
         */
        fun extractLyricsBrowseId(root: JSONObject): String? {
            val found = mutableListOf<String>()
            collectBrowseIds(root, found)
            return found.firstOrNull { it.startsWith("MPLYt_") }
                ?: found.firstOrNull { it.startsWith("MPLY") }
        }

        private fun collectBrowseIds(node: Any?, out: MutableList<String>) {
            when (node) {
                is JSONObject -> {
                    val bid = node.optString("browseId", "")
                    if (bid.startsWith("MPLY") && bid !in out) {
                        out.add(bid)
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        collectBrowseIds(node.opt(keys.next()), out)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        collectBrowseIds(node.opt(i), out)
                    }
                }
            }
        }

        /**
         * Parses a YouTube Music /browse response into a [TrackLyrics] model.
         * Supports synced timedLyricsData (MPLYt_...) and static lyrics (musicDescriptionShelfRenderer
         * or ANDROID_MUSIC element description blocks).
         */
        fun parseBrowseLyricsResponse(
            json: JSONObject,
            videoId: String?,
            title: String,
            artist: String,
            durationMs: Long
        ): TrackLyrics? {
            val timedContainer = findObjectContainingKey(json, "timedLyricsData")
            if (timedContainer != null) {
                val arr = timedContainer.optJSONArray("timedLyricsData")
                val sourceMessage = timedContainer.optString("sourceMessage", "").ifBlank { null }
                if (arr != null && arr.length() > 0) {
                    val lines = parseTimedLyricsArray(arr)
                    if (lines.isNotEmpty()) {
                        return TrackLyrics(
                            videoId = videoId,
                            title = title,
                            artist = artist,
                            durationMs = durationMs,
                            lines = lines,
                            isSynced = true,
                            sourceMessage = sourceMessage
                        )
                    }
                }
            }

            // Fallback 1: static unsynced YouTube Music lyrics (musicDescriptionShelfRenderer)
            val shelf = findObjectContainingKey(json, "musicDescriptionShelfRenderer")
                ?.optJSONObject("musicDescriptionShelfRenderer")
            if (shelf != null) {
                val descText = extractRunsText(shelf.optJSONObject("description"))
                val footerText = extractRunsText(shelf.optJSONObject("footer")).ifBlank { null }
                buildStaticLyrics(descText, footerText, videoId, title, artist, durationMs)?.let { return it }
            }

            // Fallback 2: ANDROID_MUSIC element-based static lyrics (e.g. lyricsData / description.content)
            val descHolder = findObjectContainingKey(json, "description")
            if (descHolder != null) {
                val descObj = descHolder.optJSONObject("description")
                val descText = descObj?.optString("content", "")?.takeIf { it.contains('\n') }
                    ?: extractRunsText(descObj).takeIf { it.contains('\n') }
                    ?: descHolder.optString("description", "").takeIf { it.contains('\n') }
                if (!descText.isNullOrBlank()) {
                    val footer = descHolder.optString("sourceMessage", "").ifBlank { null }
                    buildStaticLyrics(descText, footer, videoId, title, artist, durationMs)?.let { return it }
                }
            }

            return null
        }

        private fun buildStaticLyrics(
            descText: String,
            footerText: String?,
            videoId: String?,
            title: String,
            artist: String,
            durationMs: Long
        ): TrackLyrics? {
            val rawLines = descText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (rawLines.size < 2) return null
            val lines = rawLines.mapIndexed { idx, line ->
                LyricLine(
                    text = line,
                    startTimeMs = idx * 1000L,
                    endTimeMs = (idx + 1) * 1000L,
                    isInterlude = isInterludeMarker(line)
                )
            }
            return TrackLyrics(
                videoId = videoId,
                title = title,
                artist = artist,
                durationMs = durationMs,
                lines = lines,
                isSynced = false,
                sourceMessage = footerText
            )
        }

        private fun parseTimedLyricsArray(array: JSONArray): List<LyricLine> {
            val raw = ArrayList<LyricLine>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val text = item.optString("lyricLine", "").trim()
                val cueRange = item.optJSONObject("cueRange") ?: continue
                val startMs = cueRange.optString("startTimeMilliseconds", "").toLongOrNull()
                    ?: cueRange.optLong("startTimeMilliseconds", -1L)
                val endMs = cueRange.optString("endTimeMilliseconds", "").toLongOrNull()
                    ?: cueRange.optLong("endTimeMilliseconds", startMs)
                if (startMs < 0L) continue

                val interlude = isInterludeMarker(text)
                if (text.isEmpty() && !interlude) continue

                raw.add(
                    LyricLine(
                        text = if (interlude) "♪" else text,
                        startTimeMs = startMs,
                        endTimeMs = maxOf(endMs, startMs),
                        isInterlude = interlude
                    )
                )
            }
            return raw.sortedBy { it.startTimeMs }
        }

        /**
         * Parses search candidates from ANDROID_MUSIC (`musicTwoColumnItemRenderer`) AND
         * WEB_REMIX (`musicResponsiveListItemRenderer`), with a final raw videoId fallback
         * so search never returns empty even if YouTube Music introduces new renderer wrappers.
         */
        fun parseSearchCandidates(
            json: JSONObject,
            expectedTitle: String,
            expectedArtist: String,
            expectedAlbum: String,
            expectedDurationMs: Long
        ): List<SearchCandidate> {
            val seen = HashSet<String>()
            val results = mutableListOf<SearchCandidate>()

            // 1. ANDROID_MUSIC primary renderer: musicTwoColumnItemRenderer
            val twoColumnItems = mutableListOf<JSONObject>()
            collectKeyObjects(json, "musicTwoColumnItemRenderer", twoColumnItems)
            for ((idx, item) in twoColumnItems.withIndex()) {
                val videoId = item.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId", "")
                    ?.takeIf { isValidVideoId(it) }
                    ?: findFirstStringByKey(item, "videoId")?.takeIf { isValidVideoId(it) }
                    ?: continue
                if (!seen.add(videoId)) continue

                val candidateTitle = extractRunsText(item.optJSONObject("title"))
                val subtitle = extractRunsText(item.optJSONObject("subtitle"))
                val parsedArtist = extractArtistFromSubtitle(subtitle)
                val parsedDurationMs = parseDurationMs(subtitle)

                // Slight rank bonus for YouTube Music's own top search ordering
                val rankBonus = (10 - idx).coerceAtLeast(0) * 0.8
                val score = scoreCandidate(
                    candidateTitle = candidateTitle,
                    candidateSubtitle = subtitle,
                    candidateDurationMs = parsedDurationMs,
                    expectedTitle = expectedTitle,
                    expectedArtist = expectedArtist,
                    expectedAlbum = expectedAlbum,
                    expectedDurationMs = expectedDurationMs
                ) + rankBonus

                results.add(
                    SearchCandidate(
                        videoId = videoId,
                        title = candidateTitle,
                        subtitle = subtitle,
                        parsedArtist = parsedArtist,
                        durationMs = parsedDurationMs,
                        score = score
                    )
                )
            }

            // 2. WEB_REMIX / legacy renderer: musicResponsiveListItemRenderer
            val responsiveItems = mutableListOf<JSONObject>()
            collectKeyObjects(json, "musicResponsiveListItemRenderer", responsiveItems)
            for ((idx, item) in responsiveItems.withIndex()) {
                val videoId = findFirstStringByKey(item, "videoId")?.takeIf { isValidVideoId(it) }
                    ?: continue
                if (!seen.add(videoId)) continue

                val flexColumns = item.optJSONArray("flexColumns")
                val candidateTitle = flexColumns?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.let(::extractRunsText)
                    .orEmpty()

                val subtitle = flexColumns?.optJSONObject(1)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.let(::extractRunsText)
                    .orEmpty()

                val parsedArtist = extractArtistFromSubtitle(subtitle)
                val parsedDurationMs = parseDurationMs(subtitle)
                val rankBonus = (10 - idx).coerceAtLeast(0) * 0.8
                val score = scoreCandidate(
                    candidateTitle = candidateTitle,
                    candidateSubtitle = subtitle,
                    candidateDurationMs = parsedDurationMs,
                    expectedTitle = expectedTitle,
                    expectedArtist = expectedArtist,
                    expectedAlbum = expectedAlbum,
                    expectedDurationMs = expectedDurationMs
                ) + rankBonus

                results.add(
                    SearchCandidate(
                        videoId = videoId,
                        title = candidateTitle,
                        subtitle = subtitle,
                        parsedArtist = parsedArtist,
                        durationMs = parsedDurationMs,
                        score = score
                    )
                )
            }

            // 3. Future-proof fallback: collect raw videoIds directly in response order
            if (results.isEmpty()) {
                val rawIds = mutableListOf<String>()
                collectAllVideoIds(json, rawIds)
                rawIds.take(5).forEachIndexed { idx, vid ->
                    if (seen.add(vid)) {
                        results.add(
                            SearchCandidate(
                                videoId = vid,
                                title = expectedTitle,
                                subtitle = expectedArtist,
                                parsedArtist = expectedArtist,
                                durationMs = expectedDurationMs,
                                score = (10 - idx).toDouble()
                            )
                        )
                    }
                }
            }

            return results.sortedByDescending { it.score }
        }

        private fun extractArtistFromSubtitle(subtitle: String): String {
            if (subtitle.isBlank()) return ""
            // ANDROID_MUSIC subtitle formats:
            // "Daft Punk ve Julian Casablancas • 5:38 • 1,2 Mr dinlenme"
            // "Song • The Weeknd • 3:20"
            val parts = subtitle.split("•").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return ""
            val first = parts.first()
            val genericTypes = setOf("song", "şarkı", "video", "single", "ep", "album", "albüm")
            return if (first.lowercase(Locale.ROOT) in genericTypes && parts.size >= 2) {
                parts[1]
            } else {
                first
            }
        }

        internal fun scoreCandidate(
            candidateTitle: String,
            candidateSubtitle: String,
            candidateDurationMs: Long,
            expectedTitle: String,
            expectedArtist: String,
            expectedAlbum: String,
            expectedDurationMs: Long
        ): Double {
            var score = 0.0
            val normCandTitle = normalizeForMatch(candidateTitle)
            val normExpTitle = normalizeForMatch(expectedTitle)
            val normCandSub = normalizeForMatch(candidateSubtitle)
            val normExpArtist = normalizeForMatch(expectedArtist)

            if (normExpTitle.isNotEmpty() && normCandTitle.isNotEmpty()) {
                // When user enters combined "Artist Song" in single query box (expectedArtist empty),
                // compare against combined candidate title + subtitle too
                if (normExpArtist.isEmpty()) {
                    val combined = "$normCandTitle $normCandSub".trim()
                    score += tokenOverlap(combined, normExpTitle) * 45.0
                }
                when {
                    normCandTitle == normExpTitle -> score += 50.0
                    normCandTitle.contains(normExpTitle) || normExpTitle.contains(normCandTitle) -> score += 32.0
                    else -> score += tokenOverlap(normCandTitle, normExpTitle) * 28.0
                }
            }

            if (normExpArtist.isNotEmpty() && normCandSub.isNotEmpty()) {
                when {
                    normCandSub.contains(normExpArtist) -> score += 30.0
                    else -> score += tokenOverlap(normCandSub, normExpArtist) * 25.0
                }
            }

            val normAlbum = normalizeForMatch(expectedAlbum)
            if (normAlbum.isNotEmpty() && normCandSub.contains(normAlbum)) {
                score += 8.0
            }

            if (expectedDurationMs > 0L && candidateDurationMs > 0L) {
                val diffMs = abs(expectedDurationMs - candidateDurationMs)
                score += when {
                    diffMs <= 2_500L -> 22.0
                    diffMs <= 6_000L -> 12.0
                    diffMs <= 15_000L -> 3.0
                    else -> -12.0
                }
            }

            return score
        }

        internal fun parseDurationMs(text: String): Long {
            val match = DURATION_REGEX.findAll(text).lastOrNull() ?: return 0L
            val a = match.groupValues[1].toLongOrNull() ?: return 0L
            val b = match.groupValues[2].toLongOrNull() ?: return 0L
            val c = match.groupValues[3].toLongOrNull()
            return if (c != null) {
                (a * 3600L + b * 60L + c) * 1000L
            } else {
                (a * 60L + b) * 1000L
            }
        }

        private fun normalizeForMatch(input: String): String {
            return input.lowercase(Locale.ROOT)
                .replace(Regex("""\([^)]*\)|\[[^\]]*]"""), " ")
                .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        private fun tokenOverlap(a: String, b: String): Double {
            val tokensA = a.split(' ').filter { it.isNotBlank() }.toSet()
            val tokensB = b.split(' ').filter { it.isNotBlank() }.toSet()
            if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
            val common = tokensA.intersect(tokensB).size
            return common.toDouble() / maxOf(tokensA.size, tokensB.size).toDouble()
        }

        private fun isInterludeMarker(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return true
            return trimmed.all { it == '♪' || it == '♫' || it == '♬' || it == '•' || it == '·' || it.isWhitespace() }
        }

        private fun extractRunsText(obj: JSONObject?): String {
            if (obj == null) return ""
            obj.optString("simpleText", "").takeIf { it.isNotEmpty() }?.let { return it }
            val runs = obj.optJSONArray("runs") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                sb.append(runs.optJSONObject(i)?.optString("text", "").orEmpty())
            }
            return sb.toString()
        }

        private fun findObjectContainingKey(node: Any?, key: String): JSONObject? {
            when (node) {
                is JSONObject -> {
                    if (node.has(key)) return node
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        findObjectContainingKey(node.opt(keys.next()), key)?.let { return it }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        findObjectContainingKey(node.opt(i), key)?.let { return it }
                    }
                }
            }
            return null
        }

        private fun collectKeyObjects(node: Any?, key: String, out: MutableList<JSONObject>) {
            when (node) {
                is JSONObject -> {
                    if (node.has(key)) {
                        node.optJSONObject(key)?.let(out::add)
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        collectKeyObjects(node.opt(keys.next()), key, out)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        collectKeyObjects(node.opt(i), key, out)
                    }
                }
            }
        }

        private fun collectAllVideoIds(node: Any?, out: MutableList<String>) {
            when (node) {
                is JSONObject -> {
                    val v = node.opt("videoId")
                    if (v is String && isValidVideoId(v) && v !in out) {
                        out.add(v)
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        collectAllVideoIds(node.opt(keys.next()), out)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        collectAllVideoIds(node.opt(i), out)
                    }
                }
            }
        }

        private fun findFirstStringByKey(node: Any?, key: String): String? {
            when (node) {
                is JSONObject -> {
                    val value = node.opt(key)
                    if (value is String && value.isNotBlank()) return value
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        findFirstStringByKey(node.opt(keys.next()), key)?.let { return it }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        findFirstStringByKey(node.opt(i), key)?.let { return it }
                    }
                }
            }
            return null
        }
    }
}
