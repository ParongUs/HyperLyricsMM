package com.hyperlyrics.mm.lyrics.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Offline SQLite LRU + Pinned cache for YouTube Music & fallback lyrics.
 *
 * - Regular streamed tracks are cached up to [MAX_UNPINNED_ENTRIES] (2,000) with LRU pruning.
 * - Pinned tracks (YouTube Music Premium offline downloads, imported local LRC files,
 *   and locally transcribed tracks) are stored with `is_pinned = 1` and NEVER evicted.
 */
class LyricsCacheDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LYRICS (
                cache_key TEXT PRIMARY KEY,
                video_id TEXT,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                is_synced INTEGER NOT NULL DEFAULT 1,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lyrics_video_id ON $TABLE_LYRICS(video_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lyrics_updated_at ON $TABLE_LYRICS(updated_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lyrics_pinned ON $TABLE_LYRICS(is_pinned)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching {
                db.execSQL("ALTER TABLE $TABLE_LYRICS ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_lyrics_pinned ON $TABLE_LYRICS(is_pinned)")
            }.onFailure {
                db.execSQL("DROP TABLE IF EXISTS $TABLE_LYRICS")
                onCreate(db)
            }
        }
    }

    fun get(videoId: String?, title: String, artist: String): TrackLyrics? {
        val db = readableDatabase
        // 1. Exact videoId hit
        if (!videoId.isNullOrBlank()) {
            db.query(
                TABLE_LYRICS,
                arrayOf("payload_json", "cache_key"),
                "video_id = ?",
                arrayOf(videoId.trim()),
                null,
                null,
                "is_pinned DESC, updated_at DESC",
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val payload = cursor.getString(0)
                    val key = cursor.getString(1)
                    touchAsync(key)
                    return runCatching { json.decodeFromString<TrackLyrics>(payload) }.getOrNull()
                }
            }
        }

        // 2. Normalized title + artist hit
        val lookupKey = makeTrackKey(title, artist)
        if (lookupKey.isNotBlank()) {
            db.query(
                TABLE_LYRICS,
                arrayOf("payload_json"),
                "cache_key = ?",
                arrayOf(lookupKey),
                null,
                null,
                null,
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val payload = cursor.getString(0)
                    touchAsync(lookupKey)
                    return runCatching { json.decodeFromString<TrackLyrics>(payload) }.getOrNull()
                }
            }
        }

        // 3. Relaxed title-only match when artist differs slightly offline (e.g. "Artist - Topic")
        val cleanTitle = normalizePart(title)
        if (cleanTitle.length >= 3) {
            db.query(
                TABLE_LYRICS,
                arrayOf("payload_json", "cache_key", "artist"),
                "LOWER(TRIM(title)) = ?",
                arrayOf(cleanTitle),
                null,
                null,
                "is_pinned DESC, is_synced DESC, updated_at DESC",
                "5"
            ).use { cursor ->
                val normArtist = normalizePart(artist)
                var fallbackPayload: String? = null
                while (cursor.moveToNext()) {
                    val payload = cursor.getString(0)
                    val storedArtist = normalizePart(cursor.getString(2))
                    if (normArtist.isEmpty() || storedArtist.isEmpty() ||
                        storedArtist.contains(normArtist) || normArtist.contains(storedArtist)
                    ) {
                        touchAsync(cursor.getString(1))
                        return runCatching { json.decodeFromString<TrackLyrics>(payload) }.getOrNull()
                    }
                    if (fallbackPayload == null) fallbackPayload = payload
                }
                if (fallbackPayload != null && normArtist.isEmpty()) {
                    return runCatching { json.decodeFromString<TrackLyrics>(fallbackPayload) }.getOrNull()
                }
            }
        }

        return null
    }

    fun hasLyrics(videoId: String?, title: String, artist: String): Boolean =
        get(videoId, title, artist) != null

    fun put(lyrics: TrackLyrics, pinned: Boolean = false) {
        val key = makeTrackKey(lyrics.title, lyrics.artist).ifBlank {
            lyrics.videoId?.let { "vid:$it" } ?: return
        }
        val payload = runCatching { json.encodeToString(lyrics) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val db = writableDatabase

        // Preserve existing pin flag if already pinned
        val keepPinned = pinned || isPinned(db, key, lyrics.videoId)

        val values = ContentValues().apply {
            put("cache_key", key)
            put("video_id", lyrics.videoId)
            put("title", lyrics.title)
            put("artist", lyrics.artist)
            put("payload_json", payload)
            put("is_synced", if (lyrics.isSynced) 1 else 0)
            put("is_pinned", if (keepPinned) 1 else 0)
            put("updated_at", now)
        }
        db.insertWithOnConflict(TABLE_LYRICS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        pruneUnpinnedIfNeeded(db)
    }

    fun markPinnedByVideoId(videoId: String) {
        if (videoId.isBlank()) return
        val values = ContentValues().apply { put("is_pinned", 1) }
        writableDatabase.update(TABLE_LYRICS, values, "video_id = ?", arrayOf(videoId.trim()))
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_LYRICS", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun pinnedCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_LYRICS WHERE is_pinned = 1", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun clearAll() {
        writableDatabase.delete(TABLE_LYRICS, null, null)
    }

    private fun isPinned(db: SQLiteDatabase, key: String, videoId: String?): Boolean {
        db.rawQuery(
            "SELECT is_pinned FROM $TABLE_LYRICS WHERE cache_key = ? OR (video_id IS NOT NULL AND video_id = ?) LIMIT 1",
            arrayOf(key, videoId.orEmpty())
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) == 1
        }
    }

    private fun touchAsync(cacheKey: String) {
        runCatching {
            val values = ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
            }
            writableDatabase.update(TABLE_LYRICS, values, "cache_key = ?", arrayOf(cacheKey))
        }
    }

    private fun pruneUnpinnedIfNeeded(db: SQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM $TABLE_LYRICS
            WHERE is_pinned = 0 AND cache_key IN (
                SELECT cache_key FROM $TABLE_LYRICS
                WHERE is_pinned = 0
                ORDER BY updated_at DESC
                LIMIT -1 OFFSET $MAX_UNPINNED_ENTRIES
            )
            """.trimIndent()
        )
    }

    companion object {
        private const val DB_NAME = "hyperlyrics_mm_cache.db"
        private const val DB_VERSION = 2
        private const val TABLE_LYRICS = "ytm_lyrics_cache"
        private const val MAX_UNPINNED_ENTRIES = 2000

        fun normalizePart(value: String): String {
            return value.lowercase(Locale.ROOT)
                .removeSuffix(" - topic")
                .removeSuffix(" - konu")
                .trim()
                .replace(Regex("""\s+"""), " ")
        }

        fun makeTrackKey(title: String, artist: String): String {
            val t = normalizePart(title)
            val a = normalizePart(artist)
            return if (t.isEmpty()) "" else "$t||$a"
        }
    }
}
