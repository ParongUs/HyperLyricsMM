package com.hyperlyrics.mm.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hyperlyrics.mm.BuildConfig
import com.hyperlyrics.mm.HyperLyricsApp
import com.hyperlyrics.mm.XposedStatus
import com.hyperlyrics.mm.common.AccentColorMode
import com.hyperlyrics.mm.common.AppLanguage
import com.hyperlyrics.mm.common.AppStrings
import com.hyperlyrics.mm.common.AppStringsProvider
import com.hyperlyrics.mm.common.ExpandedThemeStyle
import com.hyperlyrics.mm.common.MinimalLyricSlot
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.lyrics.LyricsResolver
import com.hyperlyrics.mm.lyrics.local.LocalSpeechLyricsService
import com.hyperlyrics.mm.lyrics.parser.LrcParser
import com.hyperlyrics.mm.ui.components.HyperIslandLivePreviewSection
import com.hyperlyrics.mm.ui.components.SamplePreviewTrack
import com.hyperlyrics.mm.ui.theme.HyperLyricsYtmTheme
import com.hyperlyrics.mm.ui.theme.YtmColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyperLyricsYtmTheme {
                HyperLyricsMainScreen()
            }
        }
    }
}

@Composable
private fun HyperLyricsMainScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as HyperLyricsApp
    val settings by HyperLyricsApp.settings.collectAsState()
    val xposedStatus by HyperLyricsApp.xposedStatus.collectAsState()
    val speechStatus by LocalSpeechLyricsService.status.collectAsState()
    val strings = remember(settings.appLanguage) { AppStringsProvider.resolve(settings.appLanguage) }

    var previewTrack by remember { mutableStateOf(SamplePreviewTrack.defaultTrack) }
    var testQuery by remember { mutableStateOf("") }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testLoading by remember { mutableStateOf(false) }
    var syncNotice by remember { mutableStateOf<String?>(null) }
    var speechNotice by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    var cacheCount by remember { mutableIntStateOf(runCatching { app.cacheDb.count() }.getOrDefault(0)) }
    var pinnedCount by remember { mutableIntStateOf(runCatching { app.cacheDb.pinnedCount() }.getOrDefault(0)) }

    val scope = rememberCoroutineScope()
    val resolver = remember { LyricsResolver(app, app.cacheDb) }

    fun refreshCacheStats() {
        cacheCount = runCatching { app.cacheDb.count() }.getOrDefault(cacheCount)
        pinnedCount = runCatching { app.cacheDb.pinnedCount() }.getOrDefault(pinnedCount)
    }

    // SAF local lyrics folder picker
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            app.updateSettings {
                it.copy(localLrcFolderEnabled = true, localLrcTreeUri = uri.toString())
            }
        }
    }

    // MediaProjection launcher for internal audio on-device speech extraction
    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val startIntent = Intent(context, LocalSpeechLyricsService::class.java).apply {
            action = LocalSpeechLyricsService.ACTION_START
            putExtra(LocalSpeechLyricsService.EXTRA_TITLE, previewTrack.title)
            putExtra(LocalSpeechLyricsService.EXTRA_ARTIST, previewTrack.artist)
            putExtra(LocalSpeechLyricsService.EXTRA_PROJECTION_CODE, result.resultCode)
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                putExtra(LocalSpeechLyricsService.EXTRA_PROJECTION_DATA, result.data)
            }
        }
        ContextCompat.startForegroundService(context, startIntent)
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mpm != null) {
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
    }

    Scaffold(containerColor = YtmColors.Obsidian) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TopHeaderBar(xposedStatus = xposedStatus, strings = strings)

            HyperIslandLivePreviewSection(
                settings = settings,
                previewTrack = previewTrack,
                strings = strings
            )

            // Master + Language Card
            SettingsGroupCard {
                SettingSwitchRow(
                    title = strings.masterTitle,
                    subtitle = strings.masterSubtitle,
                    checked = settings.masterEnabled,
                    onCheckedChange = { enabled ->
                        app.updateSettings { it.copy(masterEnabled = enabled) }
                    }
                )
                CardDivider()
                SegmentedChoiceRow(
                    title = strings.languageTitle,
                    options = listOf(
                        AppLanguage.SYSTEM to strings.langSystem,
                        AppLanguage.ENGLISH to strings.langEnglish,
                        AppLanguage.TURKISH to strings.langTurkish
                    ),
                    selected = settings.appLanguage,
                    onSelect = { lang -> app.updateSettings { it.copy(appLanguage = lang) } }
                )
            }

            // Section 1: Expanded Hyper Island
            SectionHeader(strings.sectionExpanded)
            SettingsGroupCard {
                SettingSwitchRow(
                    title = strings.expandedSplitTitle,
                    subtitle = strings.expandedSplitSubtitle,
                    checked = settings.expandedLyricsEnabled,
                    onCheckedChange = { v -> app.updateSettings { it.copy(expandedLyricsEnabled = v) } }
                )
                CardDivider()
                SegmentedChoiceRow(
                    title = strings.themeTitle,
                    options = listOf(
                        ExpandedThemeStyle.YTM_DYNAMIC to strings.themeYtmDynamic,
                        ExpandedThemeStyle.YTM_PURE_DARK to strings.themeYtmDark,
                        ExpandedThemeStyle.HYPEROS_NATIVE to strings.themeNativeGlass
                    ),
                    selected = settings.expandedThemeStyle,
                    onSelect = { style -> app.updateSettings { it.copy(expandedThemeStyle = style) } }
                )
                CardDivider()
                SegmentedChoiceRow(
                    title = strings.accentTitle,
                    options = listOf(
                        AccentColorMode.YTM_CRIMSON to strings.accentCrimson,
                        AccentColorMode.YTM_WHITE to strings.accentWhite,
                        AccentColorMode.ALBUM_ADAPTIVE to strings.accentAlbum
                    ),
                    selected = settings.accentColorMode,
                    onSelect = { mode -> app.updateSettings { it.copy(accentColorMode = mode) } }
                )
                CardDivider()
                SettingSliderRow(
                    title = strings.splitRatioTitle,
                    valueLabel = "${settings.leftPaneRatioPercent}% / ${100 - settings.leftPaneRatioPercent}%",
                    value = settings.leftPaneRatioPercent.toFloat(),
                    valueRange = 38f..52f,
                    steps = 13,
                    onValueChange = { v ->
                        app.updateSettings { it.copy(leftPaneRatioPercent = v.roundToInt()) }
                    }
                )
                CardDivider()
                SettingSliderRow(
                    title = strings.expandedFontTitle,
                    valueLabel = "${"%.1f".format(settings.expandedFontSizeSp)} sp",
                    value = settings.expandedFontSizeSp,
                    valueRange = 11f..18f,
                    steps = 13,
                    onValueChange = { v ->
                        val snapped = (v * 2f).roundToInt() / 2f
                        app.updateSettings { it.copy(expandedFontSizeSp = snapped) }
                    }
                )
                CardDivider()
                SettingSwitchRow(
                    title = strings.showLikeTitle,
                    subtitle = strings.showLikeSubtitle,
                    checked = settings.showLikeButton,
                    onCheckedChange = { v -> app.updateSettings { it.copy(showLikeButton = v) } }
                )
                CardDivider()
                SettingSwitchRow(
                    title = strings.tapToSeekTitle,
                    subtitle = strings.tapToSeekSubtitle,
                    checked = settings.tapLyricToSeek,
                    onCheckedChange = { v -> app.updateSettings { it.copy(tapLyricToSeek = v) } }
                )
                CardDivider()
                SettingSwitchRow(
                    title = strings.interludeDotsTitle,
                    subtitle = strings.interludeDotsSubtitle,
                    checked = settings.showInterludeDots,
                    onCheckedChange = { v -> app.updateSettings { it.copy(showInterludeDots = v) } }
                )
            }

            // Section 2: Minimal / Collapsed Island
            SectionHeader(strings.sectionMinimal)
            SettingsGroupCard {
                SettingSwitchRow(
                    title = strings.minimalEnabledTitle,
                    subtitle = strings.minimalEnabledSubtitle,
                    checked = settings.minimalLyricsEnabled,
                    onCheckedChange = { v -> app.updateSettings { it.copy(minimalLyricsEnabled = v) } }
                )
                if (settings.minimalLyricsEnabled) {
                    CardDivider()
                    SegmentedChoiceRow(
                        title = strings.minimalSlotTitle,
                        options = listOf(
                            MinimalLyricSlot.RIGHT to strings.slotRight,
                            MinimalLyricSlot.LEFT to strings.slotLeft
                        ),
                        selected = settings.minimalSlot,
                        onSelect = { slot -> app.updateSettings { it.copy(minimalSlot = slot) } }
                    )
                    CardDivider()
                    SettingSliderRow(
                        title = strings.minimalFontTitle,
                        valueLabel = "${"%.1f".format(settings.minimalFontSizeSp)} sp",
                        value = settings.minimalFontSizeSp,
                        valueRange = 9.0f..13.5f,
                        steps = 8,
                        onValueChange = { v ->
                            val snapped = (v * 2f).roundToInt() / 2f
                            app.updateSettings { it.copy(minimalFontSizeSp = snapped) }
                        }
                    )
                    CardDivider()
                    SettingSliderRow(
                        title = strings.minimalMaxWidthTitle,
                        valueLabel = "${settings.minimalMaxWidthDp} dp",
                        value = settings.minimalMaxWidthDp.toFloat(),
                        valueRange = 80f..200f,
                        steps = 23,
                        onValueChange = { v ->
                            app.updateSettings { it.copy(minimalMaxWidthDp = (v / 5f).roundToInt() * 5) }
                        }
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = strings.unlockWidthTitle,
                        subtitle = strings.unlockWidthSubtitle,
                        checked = settings.unlockIslandWidthLimit,
                        onCheckedChange = { v -> app.updateSettings { it.copy(unlockIslandWidthLimit = v) } }
                    )
                }
            }

            // Section 3: Offline YouTube Music Premium Downloads
            SectionHeader(strings.sectionOfflinePremium)
            SettingsGroupCard {
                SettingSwitchRow(
                    title = strings.offlineSyncTitle,
                    subtitle = strings.offlineSyncSubtitle,
                    checked = settings.offlinePremiumSyncEnabled,
                    onCheckedChange = { v -> app.updateSettings { it.copy(offlinePremiumSyncEnabled = v) } }
                )
                CardDivider()
                SettingSwitchRow(
                    title = strings.offlineYtmDbTitle,
                    subtitle = strings.offlineYtmDbSubtitle,
                    checked = settings.offlineYtmDbReadEnabled,
                    onCheckedChange = { v -> app.updateSettings { it.copy(offlineYtmDbReadEnabled = v) } }
                )
                CardDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = strings.offlinePinnedSummary(cacheCount, pinnedCount),
                        color = YtmColors.TextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.sendBroadcast(Intent(ModuleConfig.ACTION_REQUEST_YTM_OFFLINE_SCAN))
                            }
                            syncNotice = strings.offlineSyncRequested
                            refreshCacheStats()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            tint = YtmColors.CrimsonSoft,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(text = strings.offlineSyncNowBtn, color = Color.White, fontSize = 12.5.sp)
                    }
                    if (!syncNotice.isNullOrBlank()) {
                        Text(
                            text = syncNotice!!,
                            color = YtmColors.ActiveGreen,
                            fontSize = 11.5.sp,
                            lineHeight = 15.5.sp
                        )
                    }
                }
            }

            // Section 4: Optional Fallback & Local Lyrics Extraction
            SectionHeader(strings.sectionFallback)
            SettingsGroupCard {
                SettingSwitchRow(
                    title = strings.externalFallbackTitle,
                    subtitle = strings.externalFallbackSubtitle,
                    checked = settings.externalFallbackEnabled,
                    onCheckedChange = { v -> app.updateSettings { it.copy(externalFallbackEnabled = v) } }
                )
                if (settings.externalFallbackEnabled) {
                    CardDivider()
                    SettingSwitchRow(
                        title = strings.lrclibTitle,
                        subtitle = strings.lrclibSubtitle,
                        checked = settings.fallbackLrclibEnabled,
                        onCheckedChange = { v -> app.updateSettings { it.copy(fallbackLrclibEnabled = v) } }
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = strings.ytCaptionsTitle,
                        subtitle = strings.ytCaptionsSubtitle,
                        checked = settings.fallbackYtCaptionsEnabled,
                        onCheckedChange = { v -> app.updateSettings { it.copy(fallbackYtCaptionsEnabled = v) } }
                    )
                    CardDivider()
                    SettingSwitchRow(
                        title = strings.neteaseTitle,
                        subtitle = strings.neteaseSubtitle,
                        checked = settings.fallbackNeteaseEnabled,
                        onCheckedChange = { v -> app.updateSettings { it.copy(fallbackNeteaseEnabled = v) } }
                    )
                }

                CardDivider()

                // Local .lrc / .ttml Folder + Manual Importer
                SettingSwitchRow(
                    title = strings.localLrcTitle,
                    subtitle = strings.localLrcSubtitle,
                    checked = settings.localLrcFolderEnabled,
                    onCheckedChange = { v -> app.updateSettings { it.copy(localLrcFolderEnabled = v) } }
                )
                if (settings.localLrcFolderEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (settings.localLrcTreeUri.isNotBlank()) {
                                "${strings.folderSelectedLabel}: ${settings.localLrcTreeUri.substringAfterLast("%2F").take(32)}"
                            } else {
                                strings.folderNotSelectedLabel
                            },
                            color = YtmColors.TextSecondary,
                            fontSize = 11.5.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { folderPickerLauncher.launch(null) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = YtmColors.CrimsonSoft, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(5.dp))
                                Text(strings.pickFolderBtn, color = Color.White, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { showImportDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.NoteAdd, contentDescription = null, tint = YtmColors.CrimsonSoft, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(5.dp))
                                Text(strings.importLrcBtn, color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }

                CardDivider()

                // On-Device Local Speech-to-Lyrics Extractor
                SettingSwitchRow(
                    title = strings.localSpeechTitle,
                    subtitle = strings.localSpeechSubtitle,
                    checked = settings.localSpeechExtractorEnabled,
                    onCheckedChange = { v ->
                        app.updateSettings { it.copy(localSpeechExtractorEnabled = v) }
                        if (!v && speechStatus.running) {
                            context.startService(
                                Intent(context, LocalSpeechLyricsService::class.java).apply {
                                    action = LocalSpeechLyricsService.ACTION_STOP
                                }
                            )
                        }
                    }
                )
                if (settings.localSpeechExtractorEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (speechStatus.running) {
                                    context.startService(
                                        Intent(context, LocalSpeechLyricsService::class.java).apply {
                                            action = LocalSpeechLyricsService.ACTION_STOP
                                        }
                                    )
                                    refreshCacheStats()
                                } else {
                                    if (!LocalSpeechLyricsService.isOnDeviceAsrSupported(context)) {
                                        speechNotice = strings.localSpeechUnavailable
                                        return@Button
                                    }
                                    speechNotice = null
                                    val hasAudio = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!hasAudio) {
                                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                                        if (mpm != null) {
                                            projectionLauncher.launch(mpm.createScreenCaptureIntent())
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (speechStatus.running) YtmColors.SurfaceElevated else YtmColors.Crimson,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (speechStatus.running) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = if (speechStatus.running) strings.localSpeechStopBtn else strings.localSpeechStartBtn,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (speechStatus.running) {
                            Text(
                                text = "${strings.localSpeechRunningStatus} (${speechStatus.capturedCount})",
                                color = YtmColors.ActiveGreen,
                                fontSize = 11.5.sp
                            )
                            if (speechStatus.lastLine.isNotBlank()) {
                                Text(
                                    text = "“${speechStatus.lastLine}”",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else if (!speechNotice.isNullOrBlank()) {
                            Text(text = speechNotice!!, color = YtmColors.WarningAmber, fontSize = 11.5.sp)
                        }
                    }
                }
            }

            // Section 5: Sync & Live Fetch Test
            SectionHeader(strings.sectionEngine)
            SettingsGroupCard {
                SettingSliderRow(
                    title = strings.syncOffsetTitle,
                    valueLabel = "${if (settings.syncOffsetMs > 0) "+" else ""}${settings.syncOffsetMs} ms",
                    value = settings.syncOffsetMs.toFloat(),
                    valueRange = -1500f..1500f,
                    steps = 59,
                    onValueChange = { v ->
                        val snapped = (v / 50f).roundToInt() * 50
                        app.updateSettings { it.copy(syncOffsetMs = snapped) }
                    }
                )
                CardDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = strings.liveTestTitle,
                        color = YtmColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = strings.liveTestSubtitle,
                        color = YtmColors.TextSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = testQuery,
                            onValueChange = { testQuery = it },
                            placeholder = {
                                Text(
                                    text = strings.liveTestPlaceholder,
                                    fontSize = 12.5.sp,
                                    color = YtmColors.TextMuted
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = YtmColors.Crimson,
                                unfocusedBorderColor = YtmColors.Hairline,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = YtmColors.Crimson
                            )
                        )
                        Button(
                            onClick = {
                                val q = testQuery.trim()
                                if (q.isEmpty() || testLoading) return@Button
                                testLoading = true
                                testStatus = strings.liveTestFetching
                                scope.launch {
                                    val fetched = withContext(Dispatchers.IO) {
                                        resolver.resolve(
                                            videoId = null,
                                            title = q,
                                            artist = "",
                                            settings = settings,
                                            forceRefresh = true
                                        )
                                    }
                                    testLoading = false
                                    refreshCacheStats()
                                    if (fetched != null && fetched.hasVocalLines) {
                                        previewTrack = fetched
                                        val src = fetched.sourceMessage ?: fetched.videoId ?: "YouTube Music"
                                        testStatus = strings.liveTestLoaded(fetched.lines.size, fetched.isSynced, src)
                                    } else {
                                        testStatus = strings.liveTestNotFound
                                    }
                                }
                            },
                            enabled = !testLoading && testQuery.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = YtmColors.Crimson,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.height(52.dp)
                        ) {
                            if (testLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (!testStatus.isNullOrBlank()) {
                        Text(
                            text = testStatus!!,
                            color = if (testStatus!!.startsWith("✓")) YtmColors.ActiveGreen else YtmColors.TextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }
                CardDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.offlineCacheTitle,
                            color = YtmColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = strings.offlinePinnedSummary(cacheCount, pinnedCount),
                            color = YtmColors.TextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(YtmColors.SurfaceElevated)
                            .clickable {
                                app.cacheDb.clearAll()
                                refreshCacheStats()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = YtmColors.CrimsonSoft,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = strings.clearBtn,
                            color = YtmColors.CrimsonSoft,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // LSPosed Setup Guide
            SettingsGroupCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = YtmColors.CrimsonSoft,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = strings.setupTitle,
                            color = YtmColors.TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = strings.setupBody,
                            color = YtmColors.TextSecondary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.5.sp
                        )
                    }
                }
            }

            Text(
                text = "HyperLyricsMM v${BuildConfig.VERSION_NAME} • YouTube Music × HyperOS Super Island",
                color = YtmColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 2.dp, bottom = 18.dp)
            )
        }
    }

    if (showImportDialog) {
        ImportLocalLyricsDialog(
            strings = strings,
            onDismiss = { showImportDialog = false },
            onSave = { title, artist, body ->
                val parsed = LrcParser.parseAuto(
                    content = body,
                    title = title,
                    artist = artist,
                    sourceMessage = "Local Import"
                )
                if (parsed != null && parsed.hasVocalLines) {
                    app.cacheDb.put(parsed, pinned = true)
                    previewTrack = parsed
                    testStatus = "✓ ${strings.importSavedMsg}"
                    refreshCacheStats()
                    runCatching { context.sendBroadcast(Intent(ModuleConfig.ACTION_LYRICS_UPDATED)) }
                }
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun ImportLocalLyricsDialog(
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String, body: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = YtmColors.SurfaceCard,
        title = { Text(strings.importLrcDialogTitle, color = Color.White, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text(strings.importLrcSongHint, color = YtmColors.TextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = YtmColors.Crimson,
                        unfocusedBorderColor = YtmColors.Hairline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    placeholder = { Text(strings.importLrcArtistHint, color = YtmColors.TextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = YtmColors.Crimson,
                        unfocusedBorderColor = YtmColors.Hairline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = { Text(strings.importLrcContentHint, color = YtmColors.TextMuted) },
                    minLines = 4,
                    maxLines = 8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = YtmColors.Crimson,
                        unfocusedBorderColor = YtmColors.Hairline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.trim(), artist.trim(), content) },
                enabled = title.isNotBlank() && content.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = YtmColors.Crimson)
            ) {
                Text(strings.saveBtn, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancelBtn, color = YtmColors.TextSecondary)
            }
        }
    )
}

@Composable
private fun TopHeaderBar(xposedStatus: XposedStatus, strings: AppStrings) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(YtmColors.Crimson),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "♪",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column {
                Text(
                    text = "HyperLyricsMM",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = strings.appSubtitle,
                    color = YtmColors.TextSecondary,
                    fontSize = 11.5.sp
                )
            }
        }

        val badgeColor = if (xposedStatus.connected) YtmColors.ActiveGreen else YtmColors.WarningAmber
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(badgeColor.copy(alpha = 0.14f))
                .border(1.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = if (xposedStatus.connected) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = badgeColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (xposedStatus.connected) "API ${xposedStatus.apiVersion}" else strings.lsposedInactive,
                color = badgeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = YtmColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp)
    )
}

@Composable
private fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(YtmColors.SurfaceCard)
            .border(1.dp, YtmColors.Hairline, RoundedCornerShape(18.dp)),
        content = content
    )
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        color = YtmColors.Hairline.copy(alpha = 0.7f),
        thickness = 0.8.dp,
        modifier = Modifier.padding(horizontal = 14.dp)
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                color = YtmColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = YtmColors.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 15.5.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = YtmColors.Crimson,
                uncheckedThumbColor = Color(0xFFB5B5C0),
                uncheckedTrackColor = Color(0xFF2B2B34),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun <T> SegmentedChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = YtmColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(YtmColors.SurfaceElevated)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) YtmColors.Crimson else Color.Transparent)
                        .clickable { onSelect(value) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else YtmColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = YtmColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueLabel,
                color = YtmColors.CrimsonSoft,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = YtmColors.Crimson,
                inactiveTrackColor = Color(0xFF2D2D36),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}
