package com.hyperlyrics.mm.common

import java.util.Locale

/**
 * Complete English & Turkish localization catalog for both the Companion Settings UI
 * and SystemUI-rendered Hyper Island lyric states.
 */
data class AppStrings(
    val isTurkish: Boolean,
    val appSubtitle: String,
    val lsposedInactive: String,
    val livePreviewHeader: String,
    val syncedBadge: String,
    val staticBadge: String,
    val waitingForYtm: String,
    val lyricsUnavailable: String,
    val noLyrics: String,

    // General & Language
    val masterTitle: String,
    val masterSubtitle: String,
    val languageTitle: String,
    val langSystem: String,
    val langEnglish: String,
    val langTurkish: String,

    // Expanded Island
    val sectionExpanded: String,
    val expandedSplitTitle: String,
    val expandedSplitSubtitle: String,
    val themeTitle: String,
    val themeYtmDynamic: String,
    val themeYtmDark: String,
    val themeNativeGlass: String,
    val accentTitle: String,
    val accentCrimson: String,
    val accentWhite: String,
    val accentAlbum: String,
    val splitRatioTitle: String,
    val expandedFontTitle: String,
    val showLikeTitle: String,
    val showLikeSubtitle: String,
    val tapToSeekTitle: String,
    val tapToSeekSubtitle: String,
    val interludeDotsTitle: String,
    val interludeDotsSubtitle: String,

    // Minimal Island
    val sectionMinimal: String,
    val minimalEnabledTitle: String,
    val minimalEnabledSubtitle: String,
    val minimalSlotTitle: String,
    val slotRight: String,
    val slotLeft: String,
    val minimalFontTitle: String,
    val minimalMaxWidthTitle: String,
    val unlockWidthTitle: String,
    val unlockWidthSubtitle: String,

    // Offline YouTube Premium
    val sectionOfflinePremium: String,
    val offlineSyncTitle: String,
    val offlineSyncSubtitle: String,
    val offlineYtmDbTitle: String,
    val offlineYtmDbSubtitle: String,
    val offlineSyncNowBtn: String,
    val offlineSyncRequested: String,
    val offlinePinnedSummary: (total: Int, pinned: Int) -> String,

    // Optional Fallback & Local Extraction
    val sectionFallback: String,
    val externalFallbackTitle: String,
    val externalFallbackSubtitle: String,
    val lrclibTitle: String,
    val lrclibSubtitle: String,
    val ytCaptionsTitle: String,
    val ytCaptionsSubtitle: String,
    val neteaseTitle: String,
    val neteaseSubtitle: String,

    val localLrcTitle: String,
    val localLrcSubtitle: String,
    val pickFolderBtn: String,
    val folderSelectedLabel: String,
    val folderNotSelectedLabel: String,
    val importLrcBtn: String,
    val importLrcDialogTitle: String,
    val importLrcSongHint: String,
    val importLrcArtistHint: String,
    val importLrcContentHint: String,
    val saveBtn: String,
    val cancelBtn: String,
    val importSavedMsg: String,

    val localSpeechTitle: String,
    val localSpeechSubtitle: String,
    val localSpeechStartBtn: String,
    val localSpeechStopBtn: String,
    val localSpeechRunningStatus: String,
    val localSpeechUnavailable: String,

    // Engine, Sync & Live Test
    val sectionEngine: String,
    val syncOffsetTitle: String,
    val liveTestTitle: String,
    val liveTestSubtitle: String,
    val liveTestPlaceholder: String,
    val liveTestFetching: String,
    val liveTestLoaded: (count: Int, synced: Boolean, source: String) -> String,
    val liveTestNotFound: String,
    val offlineCacheTitle: String,
    val clearBtn: String,

    // Setup Guide
    val setupTitle: String,
    val setupBody: String
)

object AppStringsProvider {
    fun resolve(language: AppLanguage): AppStrings {
        val useTurkish = when (language) {
            AppLanguage.TURKISH -> true
            AppLanguage.ENGLISH -> false
            AppLanguage.SYSTEM -> Locale.getDefault().language.equals("tr", ignoreCase = true)
        }
        return if (useTurkish) TURKISH else ENGLISH
    }

    val ENGLISH = AppStrings(
        isTurkish = false,
        appSubtitle = "Hyper Island for YouTube Music",
        lsposedInactive = "LSPosed Inactive",
        livePreviewHeader = "LIVE HYPER ISLAND PREVIEW",
        syncedBadge = "Synced",
        staticBadge = "Static",
        waitingForYtm = "Waiting for YouTube Music",
        lyricsUnavailable = "Lyrics unavailable",
        noLyrics = "No lyrics",

        masterTitle = "Enable HyperLyricsMM",
        masterSubtitle = "Enhances Hyper Island exclusively while YouTube Music is active",
        languageTitle = "App Language",
        langSystem = "System",
        langEnglish = "English",
        langTurkish = "Türkçe",

        sectionExpanded = "EXPANDED ISLAND (SPLIT PLAYER)",
        expandedSplitTitle = "Side-by-Side Flowing Lyrics",
        expandedSplitSubtitle = "Compact usable player on the left, live scrolling YouTube Music lyrics on the right",
        themeTitle = "Player Theme",
        themeYtmDynamic = "YTM Dynamic",
        themeYtmDark = "YTM Dark",
        themeNativeGlass = "Native Glass",
        accentTitle = "Accent & SeekBar Color",
        accentCrimson = "YTM Red",
        accentWhite = "White",
        accentAlbum = "Album",
        splitRatioTitle = "Player / Lyrics Split Ratio",
        expandedFontTitle = "Expanded Lyric Size",
        showLikeTitle = "Show 4th Like Button",
        showLikeSubtitle = "When off, Prev / Play / Next have extra-spacious touch targets",
        tapToSeekTitle = "Tap Lyric Line to Seek",
        tapToSeekSubtitle = "Jump directly to any synced lyric line's timestamp on tap",
        interludeDotsTitle = "Interlude Pulse Dots (• • •)",
        interludeDotsSubtitle = "Show rhythmic countdown dots during intro and instrumental gaps",

        sectionMinimal = "MINIMAL ISLAND (OPTIONAL SINGLE LINE)",
        minimalEnabledTitle = "Minimal Single-Line Lyrics",
        minimalEnabledSubtitle = "When disabled, collapsed Hyper Island stays 100% stock HyperOS",
        minimalSlotTitle = "Minimal Lyric Position",
        slotRight = "Right Side",
        slotLeft = "Left Side",
        minimalFontTitle = "Minimal Font Size",
        minimalMaxWidthTitle = "Minimal Max Width",
        unlockWidthTitle = "Unlock Island Width Limit",
        unlockWidthSubtitle = "Allows wider minimal capsules for longer single-line lyrics",

        sectionOfflinePremium = "OFFLINE YOUTUBE PREMIUM DOWNLOADS",
        offlineSyncTitle = "Auto Pre-Cache Downloaded Songs",
        offlineSyncSubtitle = "Automatically pins official timed lyrics for your YouTube Music Premium downloads so they work 100% offline",
        offlineYtmDbTitle = "Read YouTube Music Offline Storage",
        offlineYtmDbSubtitle = "Extracts cached offline lyrics & downloaded track IDs directly from YouTube Music's local database when offline",
        offlineSyncNowBtn = "Sync Downloads Now",
        offlineSyncRequested = "Requested YouTube Music offline downloads scan. Open YouTube Music briefly to complete sync.",
        offlinePinnedSummary = { total, pinned -> "$total tracks cached • $pinned pinned for offline playback" },

        sectionFallback = "FALLBACK FOR SONGS WITHOUT LYRICS (OPTIONAL)",
        externalFallbackTitle = "Search External Lyrics Sources",
        externalFallbackSubtitle = "Only used when YouTube Music itself has no lyrics for the playing track",
        lrclibTitle = "LRCLIB (Synced LRC)",
        lrclibSubtitle = "Open community database with high-accuracy synced lyrics",
        ytCaptionsTitle = "YouTube Timed Captions (CC / Auto-Transcript)",
        ytCaptionsSubtitle = "Extracts timed captions directly from YouTube for covers, live videos & underground tracks",
        neteaseTitle = "NetEase Cloud Music",
        neteaseSubtitle = "Additional global & pop synced lyrics fallback",

        localLrcTitle = "Local .lrc / .ttml Lyrics Folder",
        localLrcSubtitle = "Reads offline .lrc, .ttml or .txt lyric files from a folder on your phone",
        pickFolderBtn = "Choose Folder",
        folderSelectedLabel = "Folder linked",
        folderNotSelectedLabel = "No folder selected (also scans /sdcard/Lyrics)",
        importLrcBtn = "Paste / Import Lyrics",
        importLrcDialogTitle = "Import Local Lyrics",
        importLrcSongHint = "Song title",
        importLrcArtistHint = "Artist name",
        importLrcContentHint = "Paste synced [00:12.30] LRC or plain lyrics...",
        saveBtn = "Save & Pin Offline",
        cancelBtn = "Cancel",
        importSavedMsg = "Saved and pinned to offline cache!",

        localSpeechTitle = "On-Device Speech-to-Lyrics Extractor (Local AI)",
        localSpeechSubtitle = "Uses Android 13+ on-device offline speech recognition on internal YouTube Music audio to generate & cache lyrics locally when none exist",
        localSpeechStartBtn = "Start Local Extraction",
        localSpeechStopBtn = "Stop Extraction",
        localSpeechRunningStatus = "Listening locally to YouTube Music audio & saving timed lyrics...",
        localSpeechUnavailable = "On-device speech recognition is unavailable on this ROM/device.",

        sectionEngine = "YOUTUBE MUSIC ENGINE & LIVE TEST",
        syncOffsetTitle = "Lyric Sync Offset",
        liveTestTitle = "Live Lyrics Fetch Test",
        liveTestSubtitle = "Enter any song — tests YouTube Music (plus enabled fallback sources) and plays timed lyrics in the preview above.",
        liveTestPlaceholder = "e.g. Daft Punk Instant Crush",
        liveTestFetching = "Fetching lyrics...",
        liveTestLoaded = { count, synced, source ->
            "✓ Loaded $count lines (${if (synced) "Synced" else "Static"} • $source)"
        },
        liveTestNotFound = "✗ No lyrics found for this query",
        offlineCacheTitle = "Offline Lyrics Cache",
        clearBtn = "Clear",

        setupTitle = "LSPosed Setup Guide",
        setupBody = "1. Enable HyperLyricsMM in LSPosed Manager (LibXposed API 101+).\n" +
            "2. Check 'System UI' (com.android.systemui) and 'YouTube Music' in scope.\n" +
            "3. Restart SystemUI or reboot. Setting updates apply live."
    )

    val TURKISH = AppStrings(
        isTurkish = true,
        appSubtitle = "YouTube Music için Hyper Ada",
        lsposedInactive = "LSPosed Pasif",
        livePreviewHeader = "CANLI HYPER ADA ÖNİZLEME",
        syncedBadge = "Senkronize",
        staticBadge = "Statik",
        waitingForYtm = "YouTube Music bekleniyor",
        lyricsUnavailable = "Şarkı sözü bulunamadı",
        noLyrics = "Şarkı sözü yok",

        masterTitle = "HyperLyricsMM Modülü Aktif",
        masterSubtitle = "Yalnızca YouTube Music oynatılırken Hyper Ada düzenini geliştirir",
        languageTitle = "Uygulama Dili / Language",
        langSystem = "Sistem",
        langEnglish = "English",
        langTurkish = "Türkçe",

        sectionExpanded = "GENİŞLETİLMİŞ ADA (SPLIT OYNATICI)",
        expandedSplitTitle = "Yan Yana Şarkı Sözleri",
        expandedSplitSubtitle = "Sol tarafta kompakt kontroller & ilerleme çubuğu, sağda akan YouTube Music sözleri",
        themeTitle = "Kart Teması",
        themeYtmDynamic = "YTM Dinamik",
        themeYtmDark = "YTM Koyu",
        themeNativeGlass = "HyperOS Cam",
        accentTitle = "Vurgu & İlerleme Rengi",
        accentCrimson = "YTM Kırmızı",
        accentWhite = "Beyaz",
        accentAlbum = "Albüm",
        splitRatioTitle = "Oynatıcı / Söz Bölünme Oranı",
        expandedFontTitle = "Söz Yazı Boyutu",
        showLikeTitle = "Beğen (4. Buton) Göster",
        showLikeSubtitle = "Kapalıyken Önceki / Oynat / Sonraki butonları daha geniş alan kaplar",
        tapToSeekTitle = "Söze Dokunarak Sar (Tap-to-Seek)",
        tapToSeekSubtitle = "Sağdaki herhangi bir satıra dokununca şarkıyı o saniyeye alır",
        interludeDotsTitle = "Enstrümantal Ara Noktaları (• • •)",
        interludeDotsSubtitle = "Giriş ve uzun enstrümantal boşluklarda ritmik geri sayım noktaları",

        sectionMinimal = "MİNİMAL ADA (İSTEĞE BAĞLI TEK SATIR)",
        minimalEnabledTitle = "Minimal Adada Tek Satır Sözler",
        minimalEnabledSubtitle = "Kapalıyken minimal ada tamamen orijinal HyperOS görünümünde kalır",
        minimalSlotTitle = "Minimal Söz Konumu",
        slotRight = "Sağ Taraf",
        slotLeft = "Sol Taraf",
        minimalFontTitle = "Minimal Yazı Boyutu",
        minimalMaxWidthTitle = "Minimal Maksimum Genişlik",
        unlockWidthTitle = "Ada Genişlik Sınırını Esnet",
        unlockWidthSubtitle = "Uzun tek satır sözlerin kesilmeden daha rahat görünmesini sağlar",

        sectionOfflinePremium = "ÇEVRİMDIŞI YOUTUBE PREMIUM İNDİRMELERİ",
        offlineSyncTitle = "İndirilen Şarkıları Otomatik Sabitle",
        offlineSyncSubtitle = "YouTube Music Premium ile indirdiğiniz şarkıların resmi sözlerini otomatik önbelleğe sabitler; internetsizken anında açılır",
        offlineYtmDbTitle = "YouTube Music Yerel Deposunu Oku",
        offlineYtmDbSubtitle = "İnternetsizken YouTube Music'in yerel veritabanından ve ekranından indirilen şarkıların sözlerini doğrudan çıkarır",
        offlineSyncNowBtn = "İndirilenleri Şimdi Eşitle",
        offlineSyncRequested = "YouTube Music indirilenler taraması istendi. Eşitleme için YouTube Music'i kısaca açın.",
        offlinePinnedSummary = { total, pinned -> "$total şarkı önbellekte • $pinned çevrimdışı için sabitli" },

        sectionFallback = "SÖZÜ OLMAYAN ŞARKILAR İÇİN YEDEK (OPSİYONEL)",
        externalFallbackTitle = "Dışarıdan Şarkı Sözü Bul",
        externalFallbackSubtitle = "Yalnızca YouTube Music'te sözü bulunmayan şarkılar için dış kaynakları tarar",
        lrclibTitle = "LRCLIB (Senkronize LRC)",
        lrclibSubtitle = "Geniş, ücretsiz ve yüksek doğruluklu senkronize söz veritabanı",
        ytCaptionsTitle = "YouTube Zamanlı Altyazıları (CC / Otomatik)",
        ytCaptionsSubtitle = "Cover, konser veya bağımsız videolardaki YouTube altyazılarını söz olarak çeker",
        neteaseTitle = "NetEase Cloud Music",
        neteaseSubtitle = "Ek global ve pop senkronize söz yedeği",

        localLrcTitle = "Yerel .lrc / .ttml Klasörü",
        localLrcSubtitle = "Telefondaki yerel .lrc, .ttml veya .txt dosyalarından çevrimdışı söz okur",
        pickFolderBtn = "Klasör Seç",
        folderSelectedLabel = "Klasör bağlı",
        folderNotSelectedLabel = "Klasör seçilmedi (/sdcard/Lyrics otomatik taranır)",
        importLrcBtn = "Söz Yapıştır / İçe Aktar",
        importLrcDialogTitle = "Yerel Şarkı Sözü Ekle",
        importLrcSongHint = "Şarkı adı",
        importLrcArtistHint = "Sanatçı adı",
        importLrcContentHint = "[00:12.30] zamanlı LRC veya düz şarkı sözü yapıştırın...",
        saveBtn = "Kaydet & Çevrimdışı Sabitle",
        cancelBtn = "İptal",
        importSavedMsg = "Kaydedildi ve çevrimdışı önbelleğe sabitlendi!",

        localSpeechTitle = "Cihaz İçi Yerel Ses-Söz Çıkarıcı (Offline AI)",
        localSpeechSubtitle = "Sözü olmayan şarkılarda Android 13+ cihaz içi çevrimdışı ses tanıma ile iç ses akışından yerel olarak zamanlı söz çıkarır ve kaydeder",
        localSpeechStartBtn = "Yerel Çıkarmayı Başlat",
        localSpeechStopBtn = "Çıkarmayı Durdur",
        localSpeechRunningStatus = "YouTube Music sesi yerel olarak dinleniyor ve zamanlı söz çıkarılıyor...",
        localSpeechUnavailable = "Bu cihazda/ROM'da cihaz içi çevrimdışı konuşma tanıma desteği bulunamadı.",

        sectionEngine = "YOUTUBE MUSIC MOTORU & CANLI TEST",
        syncOffsetTitle = "Senkronizasyon Gecikme Ayarı",
        liveTestTitle = "Canlı Şarkı Sözü Testi",
        liveTestSubtitle = "Herhangi bir şarkı adı yaz — YouTube Music'ten (ve açıksa dış kaynaklardan) sözleri çekip yukarıdaki canlı önizlemede oynatır.",
        liveTestPlaceholder = "Örn: Daft Punk Instant Crush",
        liveTestFetching = "Şarkı sözleri alınıyor...",
        liveTestLoaded = { count, synced, source ->
            "✓ $count satır yüklendi (${if (synced) "Senkronize" else "Statik"} • $source)"
        },
        liveTestNotFound = "✗ Bu arama için şarkı sözü bulunamadı",
        offlineCacheTitle = "Çevrimdışı Söz Önbelleği",
        clearBtn = "Temizle",

        setupTitle = "LSPosed Kurulum Adımları",
        setupBody = "1. LSPosed Manager'da HyperLyricsMM modülünü etkinleştirin (API 101+).\n" +
            "2. Kapsam (Scope) olarak 'Sistem Arayüzü' (com.android.systemui) ve 'YouTube Music' seçin.\n" +
            "3. Sistem Arayüzünü veya cihazı yeniden başlatın. Ayarlar anlık uygulanır."
    )
}
