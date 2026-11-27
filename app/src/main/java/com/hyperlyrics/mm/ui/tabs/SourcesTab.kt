package com.hyperlyrics.mm.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperlyrics.mm.common.AppStrings
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.lyrics.local.SpeechExtractorStatus
import com.hyperlyrics.mm.ui.components.CardDivider
import com.hyperlyrics.mm.ui.components.SectionHeader
import com.hyperlyrics.mm.ui.components.SettingSliderRow
import com.hyperlyrics.mm.ui.components.SettingSwitchRow
import com.hyperlyrics.mm.ui.components.SettingsGroupCard
import com.hyperlyrics.mm.ui.theme.YtmColors
import kotlin.math.roundToInt

@Composable
fun SourcesTab(
    settings: ModuleSettings,
    strings: AppStrings,
    cacheCount: Int,
    pinnedCount: Int,
    syncNotice: String?,
    speechStatus: SpeechExtractorStatus,
    speechNotice: String?,
    onUpdateSettings: ((ModuleSettings) -> ModuleSettings) -> Unit,
    onSyncOfflineNow: () -> Unit,
    onClearCache: () -> Unit,
    onPickLocalFolder: () -> Unit,
    onOpenImportDialog: () -> Unit,
    onToggleLocalSpeech: (Boolean) -> Unit,
    onSpeechButtonClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 0: Supported Music Apps (Spotify, Deezer, Apple Music, Tidal, SoundCloud, Amazon, Local)
        SectionHeader(strings.sectionMusicApps)
        SettingsGroupCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = strings.musicAppsSubtitle,
                    color = YtmColors.TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 15.5.sp
                )
                val appChips = listOf(
                    Triple("Spotify", settings.supportSpotify) { v: Boolean -> onUpdateSettings { it.copy(supportSpotify = v) } },
                    Triple("Deezer", settings.supportDeezer) { v: Boolean -> onUpdateSettings { it.copy(supportDeezer = v) } },
                    Triple("Apple Music", settings.supportAppleMusic) { v: Boolean -> onUpdateSettings { it.copy(supportAppleMusic = v) } },
                    Triple("Tidal", settings.supportTidal) { v: Boolean -> onUpdateSettings { it.copy(supportTidal = v) } },
                    Triple("SoundCloud", settings.supportSoundCloud) { v: Boolean -> onUpdateSettings { it.copy(supportSoundCloud = v) } },
                    Triple("Amazon Music", settings.supportAmazonMusic) { v: Boolean -> onUpdateSettings { it.copy(supportAmazonMusic = v) } },
                    Triple(strings.appLocalPlayersLabel, settings.supportLocalPlayers) { v: Boolean -> onUpdateSettings { it.copy(supportLocalPlayers = v) } }
                )
                appChips.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { (label, checked, onToggle) ->
                            MusicAppToggleChip(
                                label = label,
                                selected = checked,
                                onClick = { onToggle(!checked) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            CardDivider()
            SettingSwitchRow(
                title = strings.appAllMediaTitle,
                subtitle = strings.appAllMediaSubtitle,
                checked = settings.supportAllMediaApps,
                onCheckedChange = { v -> onUpdateSettings { it.copy(supportAllMediaApps = v) } }
            )
        }

        // Section 1: Offline YouTube Music Premium Downloads & Cache
        SectionHeader(strings.sectionOfflinePremium)
        SettingsGroupCard {
            SettingSwitchRow(
                title = strings.offlineSyncTitle,
                subtitle = strings.offlineSyncSubtitle,
                checked = settings.offlinePremiumSyncEnabled,
                onCheckedChange = { v -> onUpdateSettings { it.copy(offlinePremiumSyncEnabled = v) } }
            )
            CardDivider()
            SettingSwitchRow(
                title = strings.offlineYtmDbTitle,
                subtitle = strings.offlineYtmDbSubtitle,
                checked = settings.offlineYtmDbReadEnabled,
                onCheckedChange = { v -> onUpdateSettings { it.copy(offlineYtmDbReadEnabled = v) } }
            )
            CardDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                            .clickable { onClearCache() }
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = YtmColors.CrimsonSoft,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = strings.clearBtn,
                            color = YtmColors.CrimsonSoft,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                OutlinedButton(
                    onClick = onSyncOfflineNow,
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
                        text = syncNotice,
                        color = YtmColors.ActiveGreen,
                        fontSize = 11.5.sp,
                        lineHeight = 15.5.sp
                    )
                }
            }
        }

        // Section 2: Sync Timing Offset
        SettingsGroupCard {
            SettingSliderRow(
                title = strings.syncOffsetTitle,
                valueLabel = "${if (settings.syncOffsetMs > 0) "+" else ""}${settings.syncOffsetMs} ms",
                value = settings.syncOffsetMs.toFloat(),
                valueRange = -1500f..1500f,
                steps = 59,
                onValueChange = { v ->
                    val snapped = (v / 50f).roundToInt() * 50
                    onUpdateSettings { it.copy(syncOffsetMs = snapped) }
                }
            )
        }

        // Section 3: Optional External Fallbacks & Local Extraction
        SectionHeader(strings.sectionFallback)
        SettingsGroupCard {
            SettingSwitchRow(
                title = strings.externalFallbackTitle,
                subtitle = strings.externalFallbackSubtitle,
                checked = settings.externalFallbackEnabled,
                onCheckedChange = { v -> onUpdateSettings { it.copy(externalFallbackEnabled = v) } }
            )
            if (settings.externalFallbackEnabled) {
                CardDivider()
                SettingSwitchRow(
                    title = strings.lrclibTitle,
                    subtitle = strings.lrclibSubtitle,
                    checked = settings.fallbackLrclibEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(fallbackLrclibEnabled = v) } }
                )
                CardDivider()
                SettingSwitchRow(
                    title = strings.ytCaptionsTitle,
                    subtitle = strings.ytCaptionsSubtitle,
                    checked = settings.fallbackYtCaptionsEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(fallbackYtCaptionsEnabled = v) } }
                )
                CardDivider()
                SettingSwitchRow(
                    title = strings.neteaseTitle,
                    subtitle = strings.neteaseSubtitle,
                    checked = settings.fallbackNeteaseEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(fallbackNeteaseEnabled = v) } }
                )
            }

            CardDivider()

            // Local .lrc / .ttml Folder & Manual Importer
            SettingSwitchRow(
                title = strings.localLrcTitle,
                subtitle = strings.localLrcSubtitle,
                checked = settings.localLrcFolderEnabled,
                onCheckedChange = { v -> onUpdateSettings { it.copy(localLrcFolderEnabled = v) } }
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
                            onClick = onPickLocalFolder,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = YtmColors.CrimsonSoft,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(5.dp))
                            Text(strings.pickFolderBtn, color = Color.White, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onOpenImportDialog,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.NoteAdd,
                                contentDescription = null,
                                tint = YtmColors.CrimsonSoft,
                                modifier = Modifier.size(16.dp)
                            )
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
                onCheckedChange = onToggleLocalSpeech
            )
            if (settings.localSpeechExtractorEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSpeechButtonClick,
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
                        Text(text = speechNotice, color = YtmColors.WarningAmber, fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicAppToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) YtmColors.Crimson.copy(alpha = 0.16f) else YtmColors.SurfaceElevated)
            .border(
                width = 1.dp,
                color = if (selected) YtmColors.Crimson.copy(alpha = 0.55f) else YtmColors.Hairline,
                shape = RoundedCornerShape(11.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = YtmColors.CrimsonSoft,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = label,
            color = if (selected) Color.White else YtmColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}
