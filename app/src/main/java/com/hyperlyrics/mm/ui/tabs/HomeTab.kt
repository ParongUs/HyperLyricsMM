package com.hyperlyrics.mm.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperlyrics.mm.common.AppStrings
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.ui.components.CardDivider
import com.hyperlyrics.mm.ui.components.HyperIslandLivePreviewSection
import com.hyperlyrics.mm.ui.components.SectionHeader
import com.hyperlyrics.mm.ui.components.SettingSwitchRow
import com.hyperlyrics.mm.ui.components.SettingsGroupCard
import com.hyperlyrics.mm.ui.components.StatusPillChip
import com.hyperlyrics.mm.ui.theme.YtmColors

@Composable
fun HomeTab(
    settings: ModuleSettings,
    strings: AppStrings,
    previewTrack: TrackLyrics,
    cacheCount: Int,
    pinnedCount: Int,
    testQuery: String,
    testStatus: String?,
    testLoading: Boolean,
    onUpdateSettings: ((ModuleSettings) -> ModuleSettings) -> Unit,
    onTestQueryChange: (String) -> Unit,
    onRunLiveTest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Master Toggle + Quick Status Summary
        SettingsGroupCard {
            SettingSwitchRow(
                title = strings.masterTitle,
                subtitle = strings.masterSubtitle,
                checked = settings.masterEnabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(masterEnabled = enabled) }
                }
            )
            CardDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPillChip(
                    text = if (settings.pinExpandedIslandEnabled) {
                        strings.quickStatusPinnedOn
                    } else {
                        strings.quickStatusPinnedOff
                    },
                    tint = if (settings.pinExpandedIslandEnabled) YtmColors.CrimsonSoft else YtmColors.TextSecondary,
                    icon = Icons.Rounded.PushPin,
                    modifier = Modifier.weight(1f, fill = false)
                )
                StatusPillChip(
                    text = "$cacheCount • $pinnedCount",
                    tint = YtmColors.ActiveGreen,
                    icon = Icons.Rounded.OfflinePin
                )
            }
        }

        // Interactive Live Preview (Minimal Pill + Expanded Split Card with Pin Toggle)
        HyperIslandLivePreviewSection(
            settings = settings,
            previewTrack = previewTrack,
            strings = strings
        )

        // Quick Live Fetch Tester (feeds directly into the Live Preview above)
        SectionHeader(strings.sectionEngine)
        SettingsGroupCard {
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
                        onValueChange = onTestQueryChange,
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
                        onClick = onRunLiveTest,
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
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (!testStatus.isNullOrBlank()) {
                    Text(
                        text = testStatus,
                        color = if (testStatus.startsWith("✓")) YtmColors.ActiveGreen else YtmColors.TextSecondary,
                        fontSize = 11.5.sp
                    )
                }
            }
        }
    }
}
