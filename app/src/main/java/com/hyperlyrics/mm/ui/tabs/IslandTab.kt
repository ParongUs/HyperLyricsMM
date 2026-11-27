package com.hyperlyrics.mm.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hyperlyrics.mm.common.AccentColorMode
import com.hyperlyrics.mm.common.AppStrings
import com.hyperlyrics.mm.common.ExpandedThemeStyle
import com.hyperlyrics.mm.common.MinimalLyricSlot
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.ui.components.CardDivider
import com.hyperlyrics.mm.ui.components.SectionHeader
import com.hyperlyrics.mm.ui.components.SegmentedChoiceRow
import com.hyperlyrics.mm.ui.components.SettingSliderRow
import com.hyperlyrics.mm.ui.components.SettingSwitchRow
import com.hyperlyrics.mm.ui.components.SettingsGroupCard
import kotlin.math.roundToInt

@Composable
fun IslandTab(
    settings: ModuleSettings,
    strings: AppStrings,
    onUpdateSettings: ((ModuleSettings) -> ModuleSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 1: Expanded Hyper Island (Split Player + Pin/Keep-Open Float Mode)
        SectionHeader(strings.sectionExpanded)
        SettingsGroupCard {
            SettingSwitchRow(
                title = strings.expandedSplitTitle,
                subtitle = strings.expandedSplitSubtitle,
                checked = settings.expandedLyricsEnabled,
                onCheckedChange = { v -> onUpdateSettings { it.copy(expandedLyricsEnabled = v) } }
            )
            if (settings.expandedLyricsEnabled) {
                CardDivider()
                SettingSwitchRow(
                    title = strings.pinExpandedTitle,
                    subtitle = strings.pinExpandedSubtitle,
                    checked = settings.pinExpandedIslandEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(pinExpandedIslandEnabled = v) } }
                )
                CardDivider()
                SettingSwitchRow(
                    title = strings.pinExpandedButtonTitle,
                    subtitle = strings.pinExpandedButtonSubtitle,
                    checked = settings.showPinButtonOnIsland,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showPinButtonOnIsland = v) } }
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
                    onSelect = { style -> onUpdateSettings { it.copy(expandedThemeStyle = style) } }
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
                    onSelect = { mode -> onUpdateSettings { it.copy(accentColorMode = mode) } }
                )
                CardDivider()
                SettingSliderRow(
                    title = strings.splitRatioTitle,
                    valueLabel = "${settings.leftPaneRatioPercent}% / ${100 - settings.leftPaneRatioPercent}%",
                    value = settings.leftPaneRatioPercent.toFloat(),
                    valueRange = 38f..52f,
                    steps = 13,
                    onValueChange = { v ->
                        onUpdateSettings { it.copy(leftPaneRatioPercent = v.roundToInt()) }
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
                        onUpdateSettings { it.copy(expandedFontSizeSp = snapped) }
                    }
                )
                CardDivider()
                SettingSwitchRow(
                    title = strings.showLikeTitle,
                    subtitle = strings.showLikeSubtitle,
                    checked = settings.showLikeButton,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showLikeButton = v) } }
                )
                CardDivider()
                SettingSwitchRow(
                    title = strings.tapToSeekTitle,
                    subtitle = strings.tapToSeekSubtitle,
                    checked = settings.tapLyricToSeek,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(tapLyricToSeek = v) } }
                )
                CardDivider()
                SettingSwitchRow(
                    title = strings.interludeDotsTitle,
                    subtitle = strings.interludeDotsSubtitle,
                    checked = settings.showInterludeDots,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showInterludeDots = v) } }
                )
            }
        }

        // Section 2: Minimal / Collapsed Island
        SectionHeader(strings.sectionMinimal)
        SettingsGroupCard {
            SettingSwitchRow(
                title = strings.minimalEnabledTitle,
                subtitle = strings.minimalEnabledSubtitle,
                checked = settings.minimalLyricsEnabled,
                onCheckedChange = { v -> onUpdateSettings { it.copy(minimalLyricsEnabled = v) } }
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
                    onSelect = { slot -> onUpdateSettings { it.copy(minimalSlot = slot) } }
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
                        onUpdateSettings { it.copy(minimalFontSizeSp = snapped) }
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
                        onUpdateSettings { it.copy(minimalMaxWidthDp = (v / 5f).roundToInt() * 5) }
                    }
                )
                CardDivider()
                SettingSwitchRow(
                    title = strings.unlockWidthTitle,
                    subtitle = strings.unlockWidthSubtitle,
                    checked = settings.unlockIslandWidthLimit,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(unlockIslandWidthLimit = v) } }
                )
            }
        }
    }
}
