package com.hyperlyrics.mm.common

import android.content.SharedPreferences

enum class AppLanguage(val id: Int) {
    SYSTEM(0),
    ENGLISH(1),
    TURKISH(2);

    companion object {
        fun fromId(id: Int): AppLanguage =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class ExpandedThemeStyle(val id: Int) {
    YTM_DYNAMIC(0),
    YTM_PURE_DARK(1),
    HYPEROS_NATIVE(2);

    companion object {
        fun fromId(id: Int): ExpandedThemeStyle =
            entries.firstOrNull { it.id == id } ?: YTM_DYNAMIC
    }
}

enum class AccentColorMode(val id: Int) {
    YTM_WHITE(0),
    YTM_CRIMSON(1),
    ALBUM_ADAPTIVE(2);

    companion object {
        fun fromId(id: Int): AccentColorMode =
            entries.firstOrNull { it.id == id } ?: YTM_CRIMSON
    }
}

enum class MinimalLyricSlot(val id: Int) {
    RIGHT(0),
    LEFT(1);

    companion object {
        fun fromId(id: Int): MinimalLyricSlot =
            entries.firstOrNull { it.id == id } ?: RIGHT
    }
}

data class ModuleSettings(
    val masterEnabled: Boolean = true,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,

    // Expanded Hyper Island (Split compact player + flowing lyrics)
    val expandedLyricsEnabled: Boolean = true,
    val pinExpandedIslandEnabled: Boolean = false,
    val showPinButtonOnIsland: Boolean = true,
    val expandedThemeStyle: ExpandedThemeStyle = ExpandedThemeStyle.YTM_DYNAMIC,
    val accentColorMode: AccentColorMode = AccentColorMode.YTM_CRIMSON,
    val leftPaneRatioPercent: Int = 45, // 38..52 (left compact player width %)
    val expandedFontSizeSp: Float = 14.5f, // 11..18
    val showLikeButton: Boolean = true,
    val tapLyricToSeek: Boolean = true,
    val showInterludeDots: Boolean = true,

    // Minimal / Collapsed Hyper Island (Optional smaller single-line lyric)
    val minimalLyricsEnabled: Boolean = true,
    val minimalSlot: MinimalLyricSlot = MinimalLyricSlot.RIGHT,
    val minimalFontSizeSp: Float = 11.0f, // 9..13.5
    val minimalMaxWidthDp: Int = 132, // 80..200
    val unlockIslandWidthLimit: Boolean = true,

    // Offline YouTube Music Premium Downloads
    val offlinePremiumSyncEnabled: Boolean = true,
    val offlineYtmDbReadEnabled: Boolean = true,

    // Optional Fallback for Songs Without Official YouTube Music Lyrics
    val externalFallbackEnabled: Boolean = false,
    val fallbackLrclibEnabled: Boolean = true,
    val fallbackYtCaptionsEnabled: Boolean = true,
    val fallbackNeteaseEnabled: Boolean = false,

    // Optional Local Lyrics (.lrc/.ttml folder & On-Device Local Speech Extractor)
    val localLrcFolderEnabled: Boolean = false,
    val localLrcTreeUri: String = "",
    val localSpeechExtractorEnabled: Boolean = false,

    // Sync
    val syncOffsetMs: Int = 0 // -1500..+1500
) {
    val leftPaneFraction: Float
        get() = leftPaneRatioPercent.coerceIn(38, 52) / 100f
}

object ModuleConfig {
    const val PREFS_NAME = "hyperlyrics_mm_prefs"
    const val MODULE_PACKAGE = "io.github.parongus.hyperlyricsmm"
    const val FEEDBACK_EMAIL = "mehmeteymensevik@gmail.com"
    const val GITHUB_REPO_URL = "https://github.com/ParongUs/HyperLyricsMM"
    const val GITHUB_ISSUES_URL = "https://github.com/ParongUs/HyperLyricsMM/issues"
    const val BRIDGE_AUTHORITY = "io.github.parongus.hyperlyricsmm.lyrics.bridge"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val SYSTEM_UI_PLUGIN_PACKAGE = "miui.systemui.plugin"
    const val YT_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"

    const val ACTION_LYRICS_UPDATED = "io.github.parongus.hyperlyricsmm.ACTION_LYRICS_UPDATED"
    const val ACTION_SETTINGS_CHANGED = "io.github.parongus.hyperlyricsmm.ACTION_SETTINGS_CHANGED"
    const val ACTION_REQUEST_YTM_OFFLINE_SCAN = "io.github.parongus.hyperlyricsmm.ACTION_REQUEST_YTM_OFFLINE_SCAN"

    // Preference Keys
    const val KEY_MASTER_ENABLED = "master_enabled"
    const val KEY_APP_LANGUAGE = "app_language"
    const val KEY_EXPANDED_LYRICS_ENABLED = "expanded_lyrics_enabled"
    const val KEY_PIN_EXPANDED_ISLAND_ENABLED = "pin_expanded_island_enabled"
    const val KEY_SHOW_PIN_BUTTON_ON_ISLAND = "show_pin_button_on_island"
    const val KEY_EXPANDED_THEME_STYLE = "expanded_theme_style"
    const val KEY_ACCENT_COLOR_MODE = "accent_color_mode"
    const val KEY_LEFT_PANE_RATIO_PERCENT = "left_pane_ratio_percent"
    const val KEY_EXPANDED_FONT_SIZE_SP = "expanded_font_size_sp"
    const val KEY_SHOW_LIKE_BUTTON = "show_like_button"
    const val KEY_TAP_LYRIC_TO_SEEK = "tap_lyric_to_seek"
    const val KEY_SHOW_INTERLUDE_DOTS = "show_interlude_dots"

    const val KEY_MINIMAL_LYRICS_ENABLED = "minimal_lyrics_enabled"
    const val KEY_MINIMAL_SLOT = "minimal_slot"
    const val KEY_MINIMAL_FONT_SIZE_SP = "minimal_font_size_sp"
    const val KEY_MINIMAL_MAX_WIDTH_DP = "minimal_max_width_dp"
    const val KEY_UNLOCK_ISLAND_WIDTH_LIMIT = "unlock_island_width_limit"

    const val KEY_OFFLINE_PREMIUM_SYNC_ENABLED = "offline_premium_sync_enabled"
    const val KEY_OFFLINE_YTM_DB_READ_ENABLED = "offline_ytm_db_read_enabled"

    const val KEY_EXTERNAL_FALLBACK_ENABLED = "external_fallback_enabled"
    const val KEY_FALLBACK_LRCLIB_ENABLED = "fallback_lrclib_enabled"
    const val KEY_FALLBACK_YT_CAPTIONS_ENABLED = "fallback_yt_captions_enabled"
    const val KEY_FALLBACK_NETEASE_ENABLED = "fallback_netease_enabled"

    const val KEY_LOCAL_LRC_FOLDER_ENABLED = "local_lrc_folder_enabled"
    const val KEY_LOCAL_LRC_TREE_URI = "local_lrc_tree_uri"
    const val KEY_LOCAL_SPEECH_EXTRACTOR_ENABLED = "local_speech_extractor_enabled"

    const val KEY_SYNC_OFFSET_MS = "sync_offset_ms"

    // YouTube Music Brand Palette
    const val YTM_BG_OBSIDIAN = 0xFF030303.toInt()
    const val YTM_SURFACE_DARK = 0xFF121212.toInt()
    const val YTM_CRIMSON = 0xFFFF0033.toInt()
    const val YTM_CRIMSON_SOFT = 0xFFFF3D5E.toInt()

    fun isYouTubeMusicPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName == YT_MUSIC_PACKAGE ||
            packageName == "app.rvx.android.apps.youtube.music" ||
            packageName == "app.revanced.android.apps.youtube.music" ||
            packageName.endsWith(".youtube.music")
    }

    fun readSettings(prefs: SharedPreferences?): ModuleSettings {
        if (prefs == null) return ModuleSettings()
        return ModuleSettings(
            masterEnabled = prefs.getBoolean(KEY_MASTER_ENABLED, true),
            appLanguage = AppLanguage.fromId(prefs.getInt(KEY_APP_LANGUAGE, AppLanguage.SYSTEM.id)),
            expandedLyricsEnabled = prefs.getBoolean(KEY_EXPANDED_LYRICS_ENABLED, true),
            pinExpandedIslandEnabled = prefs.getBoolean(KEY_PIN_EXPANDED_ISLAND_ENABLED, false),
            showPinButtonOnIsland = prefs.getBoolean(KEY_SHOW_PIN_BUTTON_ON_ISLAND, true),
            expandedThemeStyle = ExpandedThemeStyle.fromId(
                prefs.getInt(KEY_EXPANDED_THEME_STYLE, ExpandedThemeStyle.YTM_DYNAMIC.id)
            ),
            accentColorMode = AccentColorMode.fromId(
                prefs.getInt(KEY_ACCENT_COLOR_MODE, AccentColorMode.YTM_CRIMSON.id)
            ),
            leftPaneRatioPercent = prefs.getInt(KEY_LEFT_PANE_RATIO_PERCENT, 45).coerceIn(38, 52),
            expandedFontSizeSp = prefs.getFloat(KEY_EXPANDED_FONT_SIZE_SP, 14.5f).coerceIn(11f, 18f),
            showLikeButton = prefs.getBoolean(KEY_SHOW_LIKE_BUTTON, true),
            tapLyricToSeek = prefs.getBoolean(KEY_TAP_LYRIC_TO_SEEK, true),
            showInterludeDots = prefs.getBoolean(KEY_SHOW_INTERLUDE_DOTS, true),
            minimalLyricsEnabled = prefs.getBoolean(KEY_MINIMAL_LYRICS_ENABLED, true),
            minimalSlot = MinimalLyricSlot.fromId(
                prefs.getInt(KEY_MINIMAL_SLOT, MinimalLyricSlot.RIGHT.id)
            ),
            minimalFontSizeSp = prefs.getFloat(KEY_MINIMAL_FONT_SIZE_SP, 11.0f).coerceIn(9f, 13.5f),
            minimalMaxWidthDp = prefs.getInt(KEY_MINIMAL_MAX_WIDTH_DP, 132).coerceIn(80, 200),
            unlockIslandWidthLimit = prefs.getBoolean(KEY_UNLOCK_ISLAND_WIDTH_LIMIT, true),
            offlinePremiumSyncEnabled = prefs.getBoolean(KEY_OFFLINE_PREMIUM_SYNC_ENABLED, true),
            offlineYtmDbReadEnabled = prefs.getBoolean(KEY_OFFLINE_YTM_DB_READ_ENABLED, true),
            externalFallbackEnabled = prefs.getBoolean(KEY_EXTERNAL_FALLBACK_ENABLED, false),
            fallbackLrclibEnabled = prefs.getBoolean(KEY_FALLBACK_LRCLIB_ENABLED, true),
            fallbackYtCaptionsEnabled = prefs.getBoolean(KEY_FALLBACK_YT_CAPTIONS_ENABLED, true),
            fallbackNeteaseEnabled = prefs.getBoolean(KEY_FALLBACK_NETEASE_ENABLED, false),
            localLrcFolderEnabled = prefs.getBoolean(KEY_LOCAL_LRC_FOLDER_ENABLED, false),
            localLrcTreeUri = prefs.getString(KEY_LOCAL_LRC_TREE_URI, "").orEmpty(),
            localSpeechExtractorEnabled = prefs.getBoolean(KEY_LOCAL_SPEECH_EXTRACTOR_ENABLED, false),
            syncOffsetMs = prefs.getInt(KEY_SYNC_OFFSET_MS, 0).coerceIn(-1500, 1500)
        )
    }

    fun writeSettings(editor: SharedPreferences.Editor, settings: ModuleSettings) {
        editor.putBoolean(KEY_MASTER_ENABLED, settings.masterEnabled)
        editor.putInt(KEY_APP_LANGUAGE, settings.appLanguage.id)
        editor.putBoolean(KEY_EXPANDED_LYRICS_ENABLED, settings.expandedLyricsEnabled)
        editor.putBoolean(KEY_PIN_EXPANDED_ISLAND_ENABLED, settings.pinExpandedIslandEnabled)
        editor.putBoolean(KEY_SHOW_PIN_BUTTON_ON_ISLAND, settings.showPinButtonOnIsland)
        editor.putInt(KEY_EXPANDED_THEME_STYLE, settings.expandedThemeStyle.id)
        editor.putInt(KEY_ACCENT_COLOR_MODE, settings.accentColorMode.id)
        editor.putInt(KEY_LEFT_PANE_RATIO_PERCENT, settings.leftPaneRatioPercent)
        editor.putFloat(KEY_EXPANDED_FONT_SIZE_SP, settings.expandedFontSizeSp)
        editor.putBoolean(KEY_SHOW_LIKE_BUTTON, settings.showLikeButton)
        editor.putBoolean(KEY_TAP_LYRIC_TO_SEEK, settings.tapLyricToSeek)
        editor.putBoolean(KEY_SHOW_INTERLUDE_DOTS, settings.showInterludeDots)
        editor.putBoolean(KEY_MINIMAL_LYRICS_ENABLED, settings.minimalLyricsEnabled)
        editor.putInt(KEY_MINIMAL_SLOT, settings.minimalSlot.id)
        editor.putFloat(KEY_MINIMAL_FONT_SIZE_SP, settings.minimalFontSizeSp)
        editor.putInt(KEY_MINIMAL_MAX_WIDTH_DP, settings.minimalMaxWidthDp)
        editor.putBoolean(KEY_UNLOCK_ISLAND_WIDTH_LIMIT, settings.unlockIslandWidthLimit)
        editor.putBoolean(KEY_OFFLINE_PREMIUM_SYNC_ENABLED, settings.offlinePremiumSyncEnabled)
        editor.putBoolean(KEY_OFFLINE_YTM_DB_READ_ENABLED, settings.offlineYtmDbReadEnabled)
        editor.putBoolean(KEY_EXTERNAL_FALLBACK_ENABLED, settings.externalFallbackEnabled)
        editor.putBoolean(KEY_FALLBACK_LRCLIB_ENABLED, settings.fallbackLrclibEnabled)
        editor.putBoolean(KEY_FALLBACK_YT_CAPTIONS_ENABLED, settings.fallbackYtCaptionsEnabled)
        editor.putBoolean(KEY_FALLBACK_NETEASE_ENABLED, settings.fallbackNeteaseEnabled)
        editor.putBoolean(KEY_LOCAL_LRC_FOLDER_ENABLED, settings.localLrcFolderEnabled)
        editor.putString(KEY_LOCAL_LRC_TREE_URI, settings.localLrcTreeUri)
        editor.putBoolean(KEY_LOCAL_SPEECH_EXTRACTOR_ENABLED, settings.localSpeechExtractorEnabled)
        editor.putInt(KEY_SYNC_OFFSET_MS, settings.syncOffsetMs)
    }
}
