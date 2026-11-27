package com.hyperlyrics.mm.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperlyrics.mm.common.AccentColorMode
import com.hyperlyrics.mm.common.AppStrings
import com.hyperlyrics.mm.common.ExpandedThemeStyle
import com.hyperlyrics.mm.common.MinimalLyricSlot
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.lyrics.model.LyricLine
import com.hyperlyrics.mm.lyrics.model.TrackLyrics
import com.hyperlyrics.mm.lyrics.sync.LyricsTimelineEngine
import com.hyperlyrics.mm.ui.theme.YtmColors
import kotlinx.coroutines.delay

object SamplePreviewTrack {
    val defaultTrack = TrackLyrics(
        videoId = "demo_preview",
        title = "Midnight Skyline",
        artist = "HyperLyrics Demo",
        durationMs = 36_000L,
        isSynced = true,
        sourceMessage = "YouTube Music",
        lines = listOf(
            LyricLine("Neon reflections across the glass", 0L, 3500L),
            LyricLine("City lights moving fast tonight", 3500L, 7200L),
            LyricLine("Every single beat stays right in time", 7200L, 11000L),
            LyricLine("Floating lyrics along the island edge", 11000L, 14800L),
            LyricLine("Tap any line to jump right back", 14800L, 18500L),
            LyricLine("Smooth motion even when offline", 18500L, 22400L),
            LyricLine("Crimson glow fading into midnight dark", 22400L, 26500L),
            LyricLine("Sound waves echoing through the silence", 26500L, 31000L),
            LyricLine("Ready for your next favorite track", 31000L, 36000L)
        )
    )
}

@Composable
fun HyperIslandLivePreviewSection(
    settings: ModuleSettings,
    previewTrack: TrackLyrics,
    strings: AppStrings
) {
    val maxDuration = remember(previewTrack) {
        val lastEnd = previewTrack.lines.maxOfOrNull { maxOf(it.endTimeMs, it.startTimeMs + 3500L) } ?: 36_000L
        if (previewTrack.durationMs > 0L && previewTrack.durationMs <= lastEnd + 30_000L) {
            previewTrack.durationMs
        } else {
            lastEnd.coerceAtLeast(10_000L)
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember(previewTrack) {
        val firstVocal = previewTrack.lines.firstOrNull { !it.isInterlude }?.startTimeMs ?: 0L
        mutableLongStateOf(firstVocal)
    }

    LaunchedEffect(isPlaying, previewTrack) {
        while (isPlaying) {
            delay(80L)
            val next = positionMs + 80L
            positionMs = if (next >= maxDuration) {
                previewTrack.lines.firstOrNull { !it.isInterlude }?.startTimeMs ?: 0L
            } else {
                next
            }
        }
    }

    val snapshot = remember(previewTrack, positionMs, settings.syncOffsetMs) {
        LyricsTimelineEngine.resolve(
            lyrics = previewTrack,
            rawPositionMs = positionMs,
            offsetMs = settings.syncOffsetMs,
            durationMs = maxDuration
        )
    }

    val accentColor = when (settings.accentColorMode) {
        AccentColorMode.YTM_WHITE -> Color.White
        AccentColorMode.YTM_CRIMSON -> YtmColors.Crimson
        AccentColorMode.ALBUM_ADAPTIVE -> Color(0xFFFF6B8B)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF0B0B0E))
            .border(1.dp, YtmColors.Hairline, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = strings.livePreviewHeader,
                color = YtmColors.TextMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.9.sp
            )
            val src = previewTrack.sourceMessage?.takeIf { it.isNotBlank() } ?: "YouTube Music"
            Text(
                text = "$src • ${if (previewTrack.isSynced) strings.syncedBadge else strings.staticBadge}",
                color = YtmColors.CrimsonSoft,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        MinimalIslandPillPreview(
            settings = settings,
            currentLine = snapshot.currentText.ifBlank { if (snapshot.isInterlude) "♪" else "" },
            accentColor = accentColor
        )

        Spacer(modifier = Modifier.height(12.dp))

        ExpandedIslandCardPreview(
            settings = settings,
            track = previewTrack,
            activeIndex = snapshot.activeIndex,
            positionMs = positionMs,
            durationMs = maxDuration,
            isPlaying = isPlaying,
            accentColor = accentColor,
            onTogglePlay = { isPlaying = !isPlaying },
            onSeek = { target -> positionMs = target.coerceIn(0L, maxDuration) },
            strings = strings
        )
    }
}

@Composable
private fun MinimalIslandPillPreview(
    settings: ModuleSettings,
    currentLine: String,
    accentColor: Color
) {
    val showLyric = settings.masterEnabled && settings.minimalLyricsEnabled && currentLine.isNotEmpty()

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black)
            .border(1.dp, Color(0xFF222228), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showLyric && settings.minimalSlot == MinimalLyricSlot.LEFT) {
            MinimalLyricChipText(
                text = currentLine,
                fontSizeSp = settings.minimalFontSizeSp,
                maxWidthDp = settings.minimalMaxWidthDp
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.linearGradient(listOf(YtmColors.Crimson, Color(0xFF4A1228)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(0xFF141418))
                .border(1.dp, Color(0xFF282832), CircleShape)
        )

        Spacer(modifier = Modifier.width(8.dp))

        if (showLyric && settings.minimalSlot == MinimalLyricSlot.RIGHT) {
            MinimalLyricChipText(
                text = currentLine,
                fontSizeSp = settings.minimalFontSizeSp,
                maxWidthDp = settings.minimalMaxWidthDp
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun MinimalLyricChipText(
    text: String,
    fontSizeSp: Float,
    maxWidthDp: Int
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(200)) + slideInVertically { it / 2 }) togetherWith
                (fadeOut(tween(180)) + slideOutVertically { -it / 2 })
        },
        label = "minimal_lyric_line"
    ) { line ->
        Text(
            text = line,
            color = Color(0xF5FFFFFF),
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = maxWidthDp.dp)
        )
    }
}

@Composable
private fun ExpandedIslandCardPreview(
    settings: ModuleSettings,
    track: TrackLyrics,
    activeIndex: Int,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    accentColor: Color,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    strings: AppStrings
) {
    val cardBgBrush = when (settings.expandedThemeStyle) {
        ExpandedThemeStyle.YTM_DYNAMIC -> Brush.linearGradient(
            listOf(Color(0xFF331522), Color(0xFF160E16), Color(0xFF0A0A0D))
        )
        ExpandedThemeStyle.YTM_PURE_DARK -> Brush.linearGradient(
            listOf(Color(0xFF17171A), Color(0xFF0B0B0D), Color(0xFF060608))
        )
        ExpandedThemeStyle.HYPEROS_NATIVE -> Brush.linearGradient(
            listOf(Color(0xFF20222A), Color(0xFF14151B))
        )
    }

    val splitEnabled = settings.masterEnabled && settings.expandedLyricsEnabled
    val leftWeight = settings.leftPaneFraction
    val rightWeight = (1f - leftWeight).coerceAtLeast(0.45f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(cardBgBrush)
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(26.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(if (splitEnabled) leftWeight else 1f)
                    .fillMaxHeight()
                    .padding(end = if (splitEnabled) 8.dp else 0.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFFFF0033), Color(0xFF4A0E20))))
                            .border(0.75.dp, Color(0x28FFFFFF), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            color = Color(0xB8FFFFFF),
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.Speaker,
                        contentDescription = null,
                        tint = Color(0x99FFFFFF),
                        modifier = Modifier.size(15.dp)
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = (positionMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f),
                        onValueChange = { frac -> onSeek((frac * durationMs).toLong()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            inactiveTrackColor = Color(0x38FFFFFF)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = formatTime(positionMs), color = Color(0x99FFFFFF), fontSize = 9.sp)
                        Text(text = formatTime(durationMs), color = Color(0x99FFFFFF), fontSize = 9.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = null,
                        tint = Color(0xEBFFFFFF),
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { onSeek((positionMs - 4000L).coerceAtLeast(0L)) }
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x28FFFFFF))
                            .border(0.75.dp, accentColor.copy(alpha = 0.35f), CircleShape)
                            .clickable { onTogglePlay() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        tint = Color(0xEBFFFFFF),
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { onSeek((positionMs + 4000L).coerceAtMost(durationMs)) }
                    )
                    if (settings.showLikeButton) {
                        Icon(
                            imageVector = Icons.Rounded.ThumbUp,
                            contentDescription = null,
                            tint = Color(0xCCFFFFFF),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (splitEnabled) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0x2EFFFFFF), Color(0x2EFFFFFF), Color.Transparent)
                            )
                        )
                )

                val listState = rememberLazyListState()
                LaunchedEffect(activeIndex) {
                    if (activeIndex >= 0 && activeIndex < track.lines.size) {
                        listState.animateScrollToItem(index = (activeIndex - 1).coerceAtLeast(0))
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(rightWeight)
                        .fillMaxHeight()
                        .padding(start = 10.dp)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.16f to Color.Black,
                                    0.82f to Color.Black,
                                    1f to Color.Transparent
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    if (track.lines.isEmpty()) {
                        Text(
                            text = strings.lyricsUnavailable,
                            color = Color(0x80FFFFFF),
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            item { Spacer(modifier = Modifier.height(14.dp)) }
                            itemsIndexed(track.lines) { index, line ->
                                val isActive = index == activeIndex
                                val targetAlpha = when {
                                    isActive -> 1.0f
                                    index < activeIndex -> 0.32f
                                    index == activeIndex + 1 -> 0.56f
                                    else -> 0.28f
                                }
                                val animatedAlpha by animateFloatAsState(
                                    targetValue = targetAlpha,
                                    animationSpec = tween(260),
                                    label = "lyric_alpha"
                                )
                                Text(
                                    text = if (line.isInterlude) "• • •" else line.text,
                                    color = if (line.isInterlude && isActive) accentColor else Color.White,
                                    fontSize = if (isActive) {
                                        settings.expandedFontSizeSp.sp
                                    } else {
                                        (settings.expandedFontSizeSp * 0.86f).sp
                                    },
                                    lineHeight = if (isActive) {
                                        (settings.expandedFontSizeSp * 1.24f).sp
                                    } else {
                                        (settings.expandedFontSizeSp * 1.12f).sp
                                    },
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(animatedAlpha)
                                        .clickable(enabled = settings.tapLyricToSeek && track.isSynced) {
                                            onSeek(line.startTimeMs)
                                        }
                                )
                            }
                            item {
                                Text(
                                    text = track.sourceMessage ?: "YouTube Music",
                                    color = Color(0x4DFFFFFF),
                                    fontSize = 9.sp,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "%d:%02d".format(m, s)
}
