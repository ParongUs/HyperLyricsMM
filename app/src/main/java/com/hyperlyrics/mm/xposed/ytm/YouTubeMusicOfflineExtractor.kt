package com.hyperlyrics.mm.xposed.ytm

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.ytm.YouTubeMusicInnertubeClient
import com.hyperlyrics.mm.lyrics.ytm.YouTubeProtobufLyricsScanner
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * Runs inside YouTube Music's own process (`com.google.android.apps.youtube.music` & RVX/ReVanced)
 * with read-only access to YouTube Music's app-private offline SQLite databases.
 *
 * Capabilities:
 *  1. Discovers YouTube Music Premium downloaded songs (`videoId`, `title`, `artist`)
 *     so our module can automatically pre-cache & permanently pin their timed lyrics.
 *  2. Recovers exact `videoId` from YouTube Music's offline DBs even when offline MediaMetadata
 *     omits `METADATA_KEY_MEDIA_ID`.
 *  3. Extracts cached offline lyrics directly out of YouTube Music's local entity/offline
 *     SQLite protobuf/GZIP/JSON blobs (matching both plaintext AND Base64-shifted entity keys).
 */
object YouTubeMusicOfflineExtractor {
    private const val TAG = "YtmOfflineExtractor"

    data class OfflineTrackRecord(
        val videoId: String,
        val title: String,
        val artist: String
    )

    fun scanPremiumDownloadedTracks(context: Context): List<OfflineTrackRecord> {
        val discovered = LinkedHashMap<String, OfflineTrackRecord>()
        val dbFiles = collectCandidateDatabaseFiles(context)

        for (dbFile in dbFiles) {
            inspectDatabaseForDownloads(dbFile, discovered)
        }

        ModuleLogger.i(TAG, "Scanned ${dbFiles.size} YTM DBs -> found ${discovered.size} offline tracks")
        return discovered.values.toList()
    }

    fun extractOfflineLyricsForTrack(
        context: Context,
        videoId: String?,
        title: String,
        artist: String,
        durationMs: Long = 0L
    ): TrackLyrics? {
        val dbFiles = collectCandidateDatabaseFiles(context)
        if (dbFiles.isEmpty()) return null

        // If MediaSession didn't include videoId while offline, resolve it from YTM's offline DBs first!
        val resolvedVideoId = videoId?.takeIf { YouTubeMusicInnertubeClient.isValidVideoId(it) }
            ?: resolveVideoIdByTitle(dbFiles, title)

        var staticFallback: TrackLyrics? = null
        for (dbFile in dbFiles) {
            val extracted = searchDatabaseForLyricsBlob(
                dbFile = dbFile,
                videoId = resolvedVideoId,
                title = title,
                artist = artist,
                durationMs = durationMs
            )
            if (extracted != null && extracted.hasVocalLines) {
                if (extracted.isSynced) {
                    ModuleLogger.i(TAG, "Extracted synced offline lyrics from ${dbFile.name} (${extracted.lines.size} lines)")
                    return extracted
                }
                if (staticFallback == null) staticFallback = extracted
            }
        }
        return staticFallback
    }

    private fun collectCandidateDatabaseFiles(context: Context): List<File> {
        val files = LinkedHashMap<String, File>()

        // 1. All app SQLite databases (skip journal/wal/shm/metrics/crash DBs)
        runCatching { context.databaseList().toList() }.getOrDefault(emptyList()).forEach { name ->
            val lower = name.lowercase()
            if (isCandidateDbName(lower)) {
                val f = context.getDatabasePath(name)
                if (f.exists() && f.isFile && f.canRead() && f.length() > 4096L) {
                    files[f.absolutePath] = f
                }
            }
        }

        // 2. Also inspect subdirectories in filesDir & noBackupFilesDir where modern YTM stores entity/offline DBs
        listOfNotNull(context.filesDir, runCatching { context.noBackupFilesDir }.getOrNull()).forEach { dir ->
            collectSqliteFilesInDir(dir, depth = 0, out = files)
        }

        // Prioritize entity_store and offline DBs first
        return files.values.sortedBy { file ->
            val l = file.name.lowercase()
            when {
                l.contains("entity") -> 0
                l.contains("offline") -> 1
                l.contains("lyric") || l.contains("innertube") -> 2
                else -> 3
            }
        }
    }

    private fun collectSqliteFilesInDir(dir: File, depth: Int, out: MutableMap<String, File>) {
        if (depth > 3 || !dir.exists() || !dir.isDirectory) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val lower = child.name.lowercase()
                if (lower.contains("offline") || lower.contains("entity") ||
                    lower.contains("music") || lower.contains("database") || lower.contains("store")
                ) {
                    collectSqliteFilesInDir(child, depth + 1, out)
                }
            } else if (child.isFile && child.canRead() && child.length() > 4096L) {
                val lower = child.name.lowercase()
                if ((lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.contains("entity")) &&
                    isCandidateDbName(lower)
                ) {
                    out[child.absolutePath] = child
                }
            }
        }
    }

    private fun isCandidateDbName(lower: String): Boolean {
        if (lower.endsWith("-journal") || lower.endsWith("-wal") || lower.endsWith("-shm")) return false
        if (lower.contains("firebase") || lower.contains("analytics") ||
            lower.contains("workmanager") || lower.contains("androidx.work") ||
            lower.contains("crash") || lower.contains("gms") || lower.contains("phenotype")
        ) return false
        return true
    }

    private fun resolveVideoIdByTitle(dbFiles: List<File>, title: String): String? {
        val cleanTitle = title.trim()
        if (cleanTitle.length < 3) return null
        val titleBytes = cleanTitle.toByteArray(Charsets.UTF_8)

        for (dbFile in dbFiles) {
            openReadOnly(dbFile)?.use { db ->
                val tables = listTables(db)
                for (table in tables) {
                    val lower = table.lowercase()
                    if (!lower.contains("video") && !lower.contains("track") && !lower.contains("entity")) continue
                    val cols = listColumns(db, table)
                    val idCol = cols.firstOrNull {
                        it.equals("id", true) || it.equals("video_id", true) || it.equals("key", true)
                    } ?: continue
                    val titleCol = cols.firstOrNull { it.equals("title", true) || it.equals("name", true) }
                    if (titleCol != null) {
                        runCatching {
                            db.rawQuery(
                                "SELECT \"$idCol\" FROM \"$table\" WHERE \"$titleCol\" LIKE ? LIMIT 5",
                                arrayOf("%$cleanTitle%")
                            ).use { c ->
                                while (c.moveToNext()) {
                                    extractVideoIdFromKey(c.getString(0).orEmpty())?.let { return it }
                                }
                            }
                        }
                    }
                    // Also check videosV2.offline_video_data_proto blob containing title bytes
                    val protoCol = cols.firstOrNull {
                        val l = it.lowercase()
                        l.contains("proto") || l.contains("entity") || l.contains("data")
                    }
                    if (protoCol != null) {
                        runCatching {
                            db.rawQuery(
                                "SELECT \"$idCol\", \"$protoCol\" FROM \"$table\" WHERE LENGTH(\"$protoCol\") BETWEEN 16 AND 65536 LIMIT 400",
                                null
                            ).use { c ->
                                while (c.moveToNext()) {
                                    val blob = runCatching { c.getBlob(1) }.getOrNull() ?: continue
                                    if (containsSubarray(blob, titleBytes)) {
                                        extractVideoIdFromKey(c.getString(0).orEmpty())?.let { return it }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun inspectDatabaseForDownloads(
        dbFile: File,
        out: MutableMap<String, OfflineTrackRecord>
    ) {
        openReadOnly(dbFile)?.use { db ->
            val tables = listTables(db)
            for (table in tables) {
                val lowerTable = table.lowercase()
                if (!lowerTable.contains("video") && !lowerTable.contains("track") &&
                    !lowerTable.contains("download") && !lowerTable.contains("entity")
                ) continue

                val cols = listColumns(db, table)
                val idCol = cols.firstOrNull { it.equals("id", true) || it.equals("video_id", true) || it.equals("key", true) }
                    ?: continue
                val titleCol = cols.firstOrNull { it.equals("title", true) || it.equals("name", true) }
                val artistCol = cols.firstOrNull {
                    it.equals("author", true) || it.equals("artist", true) ||
                        it.equals("channel_title", true) || it.equals("owner", true)
                }

                val selectCols = listOfNotNull(idCol, titleCol, artistCol).joinToString(",")
                runCatching {
                    db.rawQuery("SELECT $selectCols FROM \"$table\" LIMIT 600", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val rawId = cursor.getString(0)?.trim().orEmpty()
                            val vid = extractVideoIdFromKey(rawId) ?: continue
                            val t = if (titleCol != null) cursor.getString(1)?.trim().orEmpty() else ""
                            val a = if (artistCol != null) {
                                val colIdx = if (titleCol != null) 2 else 1
                                cursor.getString(colIdx)?.trim().orEmpty()
                            } else ""
                            if (!out.containsKey(vid) || (out[vid]?.title.isNullOrBlank() && t.isNotBlank())) {
                                out[vid] = OfflineTrackRecord(vid, t, a)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun searchDatabaseForLyricsBlob(
        dbFile: File,
        videoId: String?,
        title: String,
        artist: String,
        durationMs: Long
    ): TrackLyrics? {
        var staticFallback: TrackLyrics? = null
        val keyPatterns = buildEntityKeyLikePatterns(videoId)

        openReadOnly(dbFile)?.use { db ->
            val tables = listTables(db)
            for (table in tables) {
                val cols = listColumns(db, table)
                val keyCols = cols.filter {
                    val l = it.lowercase()
                    l.contains("key") || l.contains("id") || l.contains("url")
                }
                val blobCols = cols.filter {
                    val l = it.lowercase()
                    l.contains("entity") || l.contains("data") || l.contains("blob") ||
                        l.contains("proto") || l.contains("payload") || l.contains("value") || l.contains("lyric")
                }
                if (keyCols.isEmpty() || blobCols.isEmpty()) continue

                val keyCol = keyCols.first()
                val blobCol = blobCols.first()

                // 1. Key-targeted search (plaintext + all 3 Base64 alignment shifts of videoId & MPLY)
                if (keyPatterns.isNotEmpty()) {
                    val whereClause = keyPatterns.joinToString(" OR ") { "\"$keyCol\" LIKE ?" }
                    val whereArgs = keyPatterns.map { "%$it%" }.toTypedArray()

                    runCatching {
                        db.rawQuery(
                            "SELECT \"$keyCol\", \"$blobCol\" FROM \"$table\" WHERE ($whereClause) AND LENGTH(\"$blobCol\") BETWEEN 64 AND 500000 LIMIT 80",
                            whereArgs
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                val rowKey = cursor.getString(0).orEmpty()
                                val blob = runCatching { cursor.getBlob(1) }.getOrNull() ?: continue
                                val parsed = parseCandidateBlob(blob, videoId, title, artist, durationMs)
                                if (parsed != null && parsed.hasVocalLines) {
                                    val keyMatchesVid = videoId != null && (
                                        rowKey.contains(videoId) ||
                                            keyPatterns.take(4).any { p -> rowKey.contains(p) } ||
                                            containsSubarray(blob, videoId.toByteArray(Charsets.UTF_8))
                                        )
                                    if (parsed.isSynced && (videoId == null || keyMatchesVid)) {
                                        return parsed
                                    }
                                    if (staticFallback == null && (videoId == null || keyMatchesVid)) {
                                        staticFallback = parsed
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Fallback BLOB search by videoId bytes inside entity blobs (catches opaque keys!)
                if (!videoId.isNullOrBlank()) {
                    val vidHex = videoId.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }
                    runCatching {
                        db.rawQuery(
                            "SELECT \"$blobCol\" FROM \"$table\" WHERE LENGTH(\"$blobCol\") BETWEEN 200 AND 400000 AND INSTR(\"$blobCol\", X'$vidHex') > 0 LIMIT 25",
                            null
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                val blob = runCatching { cursor.getBlob(0) }.getOrNull() ?: continue
                                val parsed = parseCandidateBlob(blob, videoId, title, artist, durationMs)
                                if (parsed != null && parsed.hasVocalLines) {
                                    if (parsed.isSynced) return parsed
                                    if (staticFallback == null) staticFallback = parsed
                                }
                            }
                        }
                    }
                }
            }
        }
        return staticFallback
    }

    private fun parseCandidateBlob(
        blob: ByteArray,
        videoId: String?,
        title: String,
        artist: String,
        durationMs: Long
    ): TrackLyrics? {
        if (blob.size < 16) return null
        if (blob[0] == '{'.code.toByte()) {
            return runCatching {
                YouTubeMusicInnertubeClient.parseBrowseLyricsResponse(
                    json = JSONObject(String(blob, Charsets.UTF_8)),
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    durationMs = durationMs
                )
            }.getOrNull()
        }
        return YouTubeProtobufLyricsScanner.extractFromBlob(
            blob = blob,
            videoId = videoId,
            title = title,
            artist = artist,
            durationMs = durationMs
        )
    }

    /**
     * Generates plaintext + all 3 Base64 alignment substrings for `videoId` (and `MPLY`),
     * because YouTube's `persistent_entity_store` keys are Base64-encoded protobuf wrappers.
     */
    internal fun buildEntityKeyLikePatterns(videoId: String?): List<String> {
        val out = LinkedHashSet<String>()
        if (!videoId.isNullOrBlank() && videoId.length == 11) {
            out.add(videoId)
            val bytes = videoId.toByteArray(Charsets.UTF_8)
            // Alignment 0: bytes[0..8] -> 12 base64 chars; Alignment 1: bytes[1..6] -> 8 chars; Alignment 2: bytes[2..7] -> 8 chars
            listOf(0..8, 1..6, 2..7).forEach { range ->
                val slice = bytes.sliceArray(range)
                val b64Url = Base64.encodeToString(slice, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
                val b64Std = Base64.encodeToString(slice, Base64.NO_WRAP or Base64.NO_PADDING)
                if (b64Url.length >= 8) out.add(b64Url)
                if (b64Std.length >= 8) out.add(b64Std)
            }
        }
        out.add("MPLY")
        out.add("TVBM") // Base64("MPL") alignment 0
        return out.toList()
    }

    private fun extractVideoIdFromKey(raw: String): String? {
        if (YouTubeMusicInnertubeClient.isValidVideoId(raw)) return raw
        YouTubeMusicInnertubeClient.extractVideoIdFromUri(raw)?.let { return it }
        val match = Regex("""(?:video[:/_-]|watch[:/_-])([A-Za-z0-9_-]{11})\b""").find(raw)
        if (match != null) return match.groupValues[1]

        // Decode URL-encoded Base64 YouTube EntityStore keys (e.g. "Egs<base64>")
        val decodedUrl = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        if (decodedUrl.length in 16..160) {
            val rawBytes = runCatching { Base64.decode(decodedUrl, Base64.URL_SAFE) }.getOrNull()
                ?: runCatching { Base64.decode(decodedUrl, Base64.DEFAULT) }.getOrNull()
            if (rawBytes != null && rawBytes.size >= 13) {
                val ascii = String(rawBytes, Charsets.ISO_8859_1)
                val idMatch = Regex("""\x0B([A-Za-z0-9_-]{11})""").find(ascii)
                if (idMatch != null) return idMatch.groupValues[1]
            }
        }
        return null
    }

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun openReadOnly(file: File): SQLiteDatabase? = runCatching {
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
    }.getOrNull()

    private fun listTables(db: SQLiteDatabase): List<String> {
        val list = mutableListOf<String>()
        runCatching {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    if (!name.startsWith("sqlite_") && !name.startsWith("android_")) {
                        list.add(name)
                    }
                }
            }
        }
        return list
    }

    private fun listColumns(db: SQLiteDatabase, table: String): List<String> {
        val cols = mutableListOf<String>()
        runCatching {
            db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                if (nameIdx >= 0) {
                    while (c.moveToNext()) cols.add(c.getString(nameIdx))
                }
            }
        }
        return cols
    }
}
