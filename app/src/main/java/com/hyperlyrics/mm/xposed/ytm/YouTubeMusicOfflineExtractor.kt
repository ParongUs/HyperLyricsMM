package com.hyperlyrics.mm.xposed.ytm

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.ytm.YouTubeMusicInnertubeClient
import com.hyperlyrics.mm.lyrics.ytm.YouTubeProtobufLyricsScanner
import org.json.JSONObject
import java.io.File

/**
 * Runs inside YouTube Music's own process (`com.google.android.apps.youtube.music`)
 * with read-only access to YouTube Music's app-private offline SQLite databases.
 *
 * Capabilities:
 *  1. Discovers YouTube Music Premium downloaded songs (`videoId`, `title`, `artist`)
 *     so our module can automatically pre-cache & permanently pin their timed lyrics.
 *  2. Extracts cached offline lyrics directly out of YouTube Music's own local entity/offline
 *     SQLite protobuf/JSON blobs when the user is completely offline (airplane mode).
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
        val dbNames = runCatching { context.databaseList().toList() }.getOrDefault(emptyList())
        val candidateDbs = dbNames.filter { name ->
            val lower = name.lowercase()
            !lower.endsWith("-journal") && !lower.endsWith("-wal") && !lower.endsWith("-shm") &&
                (lower.contains("offline") || lower.contains("download") || lower.contains("entity") || lower.contains("music"))
        }

        for (dbName in candidateDbs) {
            val dbFile = context.getDatabasePath(dbName)
            if (!dbFile.exists() || !dbFile.canRead() || dbFile.length() == 0L) continue
            inspectDatabaseForDownloads(dbFile, discovered)
        }

        ModuleLogger.i(TAG, "Scanned ${candidateDbs.size} YTM DBs -> found ${discovered.size} offline tracks")
        return discovered.values.toList()
    }

    fun extractOfflineLyricsForTrack(
        context: Context,
        videoId: String?,
        title: String,
        artist: String,
        durationMs: Long = 0L
    ): TrackLyrics? {
        val dbNames = runCatching { context.databaseList().toList() }.getOrDefault(emptyList())
        val candidateDbs = dbNames.filter { name ->
            val lower = name.lowercase()
            !lower.endsWith("-journal") && !lower.endsWith("-wal") && !lower.endsWith("-shm") &&
                (lower.contains("entity") || lower.contains("offline") || lower.contains("innertube") ||
                    lower.contains("cache") || lower.contains("lyric") || lower.contains("download"))
        }

        for (dbName in candidateDbs) {
            val dbFile = context.getDatabasePath(dbName)
            if (!dbFile.exists() || !dbFile.canRead() || dbFile.length() == 0L) continue
            val extracted = searchDatabaseForLyricsBlob(
                dbFile = dbFile,
                videoId = videoId,
                title = title,
                artist = artist,
                durationMs = durationMs
            )
            if (extracted != null && extracted.hasVocalLines) {
                ModuleLogger.i(TAG, "Extracted offline lyrics from ${dbFile.name} (${extracted.lines.size} lines, synced=${extracted.isSynced})")
                return extracted
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

                val whereClause: String
                val whereArgs: Array<String>
                if (!videoId.isNullOrBlank()) {
                    whereClause = "\"$keyCol\" LIKE ? OR \"$keyCol\" LIKE ?"
                    whereArgs = arrayOf("%$videoId%", "%MPLY%")
                } else {
                    whereClause = "\"$keyCol\" LIKE ?"
                    whereArgs = arrayOf("%MPLY%")
                }

                runCatching {
                    db.rawQuery(
                        "SELECT \"$keyCol\", \"$blobCol\" FROM \"$table\" WHERE $whereClause LIMIT 40",
                        whereArgs
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val rowKey = cursor.getString(0).orEmpty()
                            val blob = runCatching { cursor.getBlob(1) }.getOrNull()
                            if (blob != null && blob.size >= 16) {
                                // Try JSON first if blob starts with '{'
                                val parsed = if (blob[0] == '{'.code.toByte()) {
                                    runCatching {
                                        YouTubeMusicInnertubeClient.parseBrowseLyricsResponse(
                                            json = JSONObject(String(blob, Charsets.UTF_8)),
                                            videoId = videoId,
                                            title = title,
                                            artist = artist,
                                            durationMs = durationMs
                                        )
                                    }.getOrNull()
                                } else {
                                    YouTubeProtobufLyricsScanner.extractFromBlob(
                                        blob = blob,
                                        videoId = videoId,
                                        title = title,
                                        artist = artist,
                                        durationMs = durationMs
                                    )
                                }
                                if (parsed != null && parsed.hasVocalLines) {
                                    // Prefer entries keyed to our exact videoId or synced MPLYt_
                                    if (parsed.isSynced && (videoId == null || rowKey.contains(videoId) || rowKey.contains("MPLYt_"))) {
                                        return parsed
                                    }
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

    private fun extractVideoIdFromKey(raw: String): String? {
        if (YouTubeMusicInnertubeClient.isValidVideoId(raw)) return raw
        YouTubeMusicInnertubeClient.extractVideoIdFromUri(raw)?.let { return it }
        // YouTube EntityStore keys often look like "Egs<base64>" or "video:<11char>"
        val match = Regex("""(?:video[:/_-]|watch[:/_-])([A-Za-z0-9_-]{11})\b""").find(raw)
        return match?.groupValues?.getOrNull(1)
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
