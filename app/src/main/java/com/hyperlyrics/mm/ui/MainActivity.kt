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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hyperlyrics.mm.HyperLyricsApp
import com.hyperlyrics.mm.XposedStatus
import com.hyperlyrics.mm.common.AppStrings
import com.hyperlyrics.mm.common.AppStringsProvider
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.lyrics.LyricsResolver
import com.hyperlyrics.mm.lyrics.local.LocalSpeechLyricsService
import com.hyperlyrics.mm.lyrics.parser.LrcParser
import com.hyperlyrics.mm.ui.components.SamplePreviewTrack
import com.hyperlyrics.mm.ui.tabs.FeedbackTab
import com.hyperlyrics.mm.ui.tabs.HomeTab
import com.hyperlyrics.mm.ui.tabs.IslandTab
import com.hyperlyrics.mm.ui.tabs.SourcesTab
import com.hyperlyrics.mm.ui.theme.HyperLyricsYtmTheme
import com.hyperlyrics.mm.ui.theme.YtmColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MainTab(val icon: ImageVector) {
    HOME(Icons.Rounded.Home),
    ISLAND(Icons.Rounded.Tune),
    SOURCES(Icons.Rounded.LibraryMusic),
    FEEDBACK(Icons.Rounded.Feedback);

    fun label(strings: AppStrings): String = when (this) {
        HOME -> strings.tabHome
        ISLAND -> strings.tabIsland
        SOURCES -> strings.tabSources
        FEEDBACK -> strings.tabFeedback
    }
}

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

    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
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

    Scaffold(
        containerColor = YtmColors.Obsidian,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(YtmColors.Obsidian)
                    .statusBarsPadding()
            ) {
                TopHeaderBar(
                    xposedStatus = xposedStatus,
                    strings = strings,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                HorizontalDivider(color = YtmColors.Hairline.copy(alpha = 0.6f), thickness = 0.8.dp)
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = YtmColors.Hairline.copy(alpha = 0.6f), thickness = 0.8.dp)
                NavigationBar(
                    containerColor = Color(0xFF0A0A0D),
                    contentColor = Color.White,
                    tonalElevation = 0.dp
                ) {
                    MainTab.entries.forEach { tab ->
                        val isSelected = tab == selectedTab
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { selectedTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label(strings),
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label(strings),
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = Color.White,
                                indicatorColor = YtmColors.Crimson.copy(alpha = 0.24f),
                                unselectedIconColor = YtmColors.TextMuted,
                                unselectedTextColor = YtmColors.TextMuted
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(
                targetState = selectedTab,
                animationSpec = tween(180),
                label = "main_tab_crossfade"
            ) { tab ->
                when (tab) {
                    MainTab.HOME -> HomeTab(
                        settings = settings,
                        strings = strings,
                        previewTrack = previewTrack,
                        cacheCount = cacheCount,
                        pinnedCount = pinnedCount,
                        testQuery = testQuery,
                        testStatus = testStatus,
                        testLoading = testLoading,
                        onUpdateSettings = app::updateSettings,
                        onTestQueryChange = { testQuery = it },
                        onRunLiveTest = {
                            val q = testQuery.trim()
                            if (q.isEmpty() || testLoading) return@HomeTab
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
                        }
                    )

                    MainTab.ISLAND -> IslandTab(
                        settings = settings,
                        strings = strings,
                        onUpdateSettings = app::updateSettings
                    )

                    MainTab.SOURCES -> SourcesTab(
                        settings = settings,
                        strings = strings,
                        cacheCount = cacheCount,
                        pinnedCount = pinnedCount,
                        syncNotice = syncNotice,
                        speechStatus = speechStatus,
                        speechNotice = speechNotice,
                        onUpdateSettings = app::updateSettings,
                        onSyncOfflineNow = {
                            runCatching {
                                context.sendBroadcast(Intent(ModuleConfig.ACTION_REQUEST_YTM_OFFLINE_SCAN))
                            }
                            syncNotice = strings.offlineSyncRequested
                            refreshCacheStats()
                        },
                        onClearCache = {
                            app.cacheDb.clearAll()
                            refreshCacheStats()
                        },
                        onPickLocalFolder = { folderPickerLauncher.launch(null) },
                        onOpenImportDialog = { showImportDialog = true },
                        onToggleLocalSpeech = { enabled ->
                            app.updateSettings { it.copy(localSpeechExtractorEnabled = enabled) }
                            if (!enabled && speechStatus.running) {
                                context.startService(
                                    Intent(context, LocalSpeechLyricsService::class.java).apply {
                                        action = LocalSpeechLyricsService.ACTION_STOP
                                    }
                                )
                            }
                        },
                        onSpeechButtonClick = {
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
                                    return@SourcesTab
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
                        }
                    )

                    MainTab.FEEDBACK -> FeedbackTab(
                        settings = settings,
                        xposedStatus = xposedStatus,
                        strings = strings,
                        onUpdateSettings = app::updateSettings
                    )
                }
            }
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
private fun TopHeaderBar(
    xposedStatus: XposedStatus,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = strings.appSubtitle,
                    color = YtmColors.TextSecondary,
                    fontSize = 11.sp
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
