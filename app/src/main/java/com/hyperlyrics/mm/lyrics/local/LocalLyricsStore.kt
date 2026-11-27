package com.hyperlyrics.mm.lyrics.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.parser.LrcParser
import java.io.File
import java.util.Locale

/**
 * Offline reader for local `.lrc`, `.ttml`, and `.txt` lyrics files stored either
 * in a user-picked SAF directory tree or standard device folders.
 */
object LocalLyricsStore {
    private const val TAG = "LocalLyricsStore"
    private val EXTENSIONS = setOf("lrc", "ttml", "txt")

    fun findLocalLyrics(
        context: Context,
        treeUriString: String,
        videoId: String?,
        title: String,
        artist: String,
        durationMs: Long = 0L
    ): TrackLyrics? {
        val normTitle = normalizeKey(title)
        val normArtist = normalizeKey(artist)
        val normId = videoId?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normTitle.isEmpty() && normId.isEmpty()) return null

        val rawContent = readFromSafTree(context, treeUriString, normId, normTitle, normArtist)
            ?: readFromStandardDirs(normId, normTitle, normArtist)
            ?: return null

        return LrcParser.parseAuto(
            content = rawContent,
            title = title,
            artist = artist,
            videoId = videoId,
            durationMs = durationMs,
            sourceMessage = "Local File"
        )?.takeIf { it.hasVocalLines }
    }

    private fun readFromSafTree(
        context: Context,
        treeUriString: String,
        normId: String,
        normTitle: String,
        normArtist: String
    ): String? {
        if (treeUriString.isBlank()) return null
        return runCatching {
            val treeUri = Uri.parse(treeUriString)
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            var bestDocId: String? = null
            var bestScore = 0
            context.contentResolver.query(childrenUri, cols, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext !in EXTENSIONS) continue
                    val stem = normalizeKey(displayName.substringBeforeLast('.'))
                    val score = scoreFileMatch(stem, ext, normId, normTitle, normArtist)
                    if (score > bestScore) {
                        bestScore = score
                        bestDocId = docId
                    }
                }
            }
            val matchedId = bestDocId ?: return@runCatching null
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, matchedId)
            context.contentResolver.openInputStream(docUri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            }
        }.onFailure {
            ModuleLogger.d(TAG, "SAF tree read skipped: ${it.message}")
        }.getOrNull()
    }

    private fun readFromStandardDirs(
        normId: String,
        normTitle: String,
        normArtist: String
    ): String? {
        val base = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return null
        val folders = listOf(
            File(base, "Lyrics"),
            File(base, "Music/Lyrics"),
            File(base, "Download/Lyrics")
        )
        var bestFile: File? = null
        var bestScore = 0
        for (dir in folders) {
            if (!dir.isDirectory || !dir.canRead()) continue
            val entries = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (file in entries) {
                if (!file.isFile || !file.canRead()) continue
                val ext = file.extension.lowercase(Locale.ROOT)
                if (ext !in EXTENSIONS) continue
                val stem = normalizeKey(file.nameWithoutExtension)
                val score = scoreFileMatch(stem, ext, normId, normTitle, normArtist)
                if (score > bestScore) {
                    bestScore = score
                    bestFile = file
                }
            }
        }
        return bestFile?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
    }

    private fun scoreFileMatch(
        stem: String,
        ext: String,
        normId: String,
        normTitle: String,
        normArtist: String
    ): Int {
        if (stem.isEmpty()) return 0
        val bonus = when (ext) {
            "lrc" -> 10
            "ttml" -> 8
            else -> 2
        }
        if (normId.isNotEmpty() && stem.equals(normId, ignoreCase = true)) return 100 + bonus
        if (normTitle.isEmpty()) return 0
        if (normArtist.isNotEmpty()) {
            if (stem == "$normArtist $normTitle" || stem == "$normTitle $normArtist") return 95 + bonus
            if (stem.contains(normTitle) && stem.contains(normArtist)) return 85 + bonus
        }
        if (stem == normTitle) return 70 + bonus
        if (normTitle.length >= 5 && stem.contains(normTitle)) return 45 + bonus
        return 0
    }

    private fun normalizeKey(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("""\([^)]*\)|\[[^\]]*]"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
