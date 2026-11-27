package com.hyperlyrics.mm.ui.tabs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperlyrics.mm.BuildConfig
import com.hyperlyrics.mm.XposedStatus
import com.hyperlyrics.mm.common.AppLanguage
import com.hyperlyrics.mm.common.AppStrings
import com.hyperlyrics.mm.common.FeedbackDeviceSnapshot
import com.hyperlyrics.mm.common.FeedbackMailFormatter
import com.hyperlyrics.mm.common.FeedbackTopic
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.ui.components.CardDivider
import com.hyperlyrics.mm.ui.components.SectionHeader
import com.hyperlyrics.mm.ui.components.SegmentedChoiceRow
import com.hyperlyrics.mm.ui.components.SettingSwitchRow
import com.hyperlyrics.mm.ui.components.SettingsGroupCard
import com.hyperlyrics.mm.ui.theme.YtmColors

@Composable
fun FeedbackTab(
    settings: ModuleSettings,
    xposedStatus: XposedStatus,
    strings: AppStrings,
    onUpdateSettings: ((ModuleSettings) -> ModuleSettings) -> Unit
) {
    val context = LocalContext.current
    var selectedTopic by remember { mutableStateOf(FeedbackTopic.BUG) }
    var feedbackMessage by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var noticeText by remember { mutableStateOf<String?>(null) }
    var noticeIsWarning by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var logsSnapshot by remember { mutableStateOf(ModuleLogger.recentLogs()) }

    val deviceSnapshot = remember(xposedStatus) {
        FeedbackDeviceSnapshot(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            romDisplay = Build.DISPLAY ?: "unknown",
            lsposedConnected = xposedStatus.connected,
            lsposedFramework = xposedStatus.frameworkName,
            lsposedApi = xposedStatus.apiVersion
        )
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun openExternalUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 1: App Language
        SectionHeader(strings.sectionPreferences)
        SettingsGroupCard {
            SegmentedChoiceRow(
                title = strings.languageTitle,
                options = listOf(
                    AppLanguage.SYSTEM to strings.langSystem,
                    AppLanguage.ENGLISH to strings.langEnglish,
                    AppLanguage.TURKISH to strings.langTurkish
                ),
                selected = settings.appLanguage,
                onSelect = { lang -> onUpdateSettings { it.copy(appLanguage = lang) } }
            )
        }

        // Section 2: Direct Email Feedback & GitHub Issues
        SectionHeader(strings.sectionFeedback)
        SettingsGroupCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Email,
                        contentDescription = null,
                        tint = YtmColors.CrimsonSoft,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = strings.feedbackTitle,
                        color = YtmColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = strings.feedbackSubtitle,
                    color = YtmColors.TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 15.5.sp
                )
                Text(
                    text = ModuleConfig.FEEDBACK_EMAIL,
                    color = YtmColors.CrimsonSoft,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            CardDivider()

            SegmentedChoiceRow(
                title = strings.feedbackTypeLabel,
                options = listOf(
                    FeedbackTopic.BUG to strings.feedbackTypeBug,
                    FeedbackTopic.FEATURE to strings.feedbackTypeFeature,
                    FeedbackTopic.GENERAL to strings.feedbackTypeGeneral
                ),
                selected = selectedTopic,
                onSelect = { selectedTopic = it }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = feedbackMessage,
                    onValueChange = { feedbackMessage = it },
                    placeholder = {
                        Text(
                            text = strings.feedbackMessageHint,
                            color = YtmColors.TextMuted,
                            fontSize = 12.5.sp
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = YtmColors.Crimson,
                        unfocusedBorderColor = YtmColors.Hairline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = YtmColors.Crimson
                    )
                )
            }

            CardDivider()

            SettingSwitchRow(
                title = strings.feedbackIncludeDiagTitle,
                subtitle = strings.feedbackIncludeDiagSubtitle,
                checked = includeDiagnostics,
                onCheckedChange = { includeDiagnostics = it }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val subject = FeedbackMailFormatter.buildSubject(selectedTopic, BuildConfig.VERSION_NAME)
                        val body = FeedbackMailFormatter.buildEmailBody(
                            userMessage = feedbackMessage,
                            includeDiagnostics = includeDiagnostics,
                            device = deviceSnapshot,
                            settings = settings,
                            recentLogs = ModuleLogger.recentLogs()
                        )
                        val mailtoUri = Uri.parse(
                            FeedbackMailFormatter.buildMailtoUriString(
                                recipient = ModuleConfig.FEEDBACK_EMAIL,
                                subject = subject,
                                body = body
                            )
                        )
                        val emailIntent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val launched = runCatching {
                            context.startActivity(emailIntent)
                        }.isSuccess
                        if (!launched) {
                            copyToClipboard("HyperLyricsMM Feedback", "${ModuleConfig.FEEDBACK_EMAIL}\n$subject\n\n$body")
                            noticeText = strings.feedbackNoEmailApp
                            noticeIsWarning = true
                        } else {
                            noticeText = null
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = YtmColors.Crimson,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = strings.feedbackSendEmailBtn,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            copyToClipboard("HyperLyricsMM Email", ModuleConfig.FEEDBACK_EMAIL)
                            noticeText = strings.feedbackEmailCopied
                            noticeIsWarning = false
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = YtmColors.CrimsonSoft,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.size(5.dp))
                        Text(strings.feedbackCopyEmailBtn, color = Color.White, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { openExternalUrl(ModuleConfig.GITHUB_ISSUES_URL) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BugReport,
                            contentDescription = null,
                            tint = YtmColors.CrimsonSoft,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.size(5.dp))
                        Text(strings.feedbackGithubIssuesBtn, color = Color.White, fontSize = 12.sp)
                    }
                }

                if (!noticeText.isNullOrBlank()) {
                    Text(
                        text = noticeText!!,
                        color = if (noticeIsWarning) YtmColors.WarningAmber else YtmColors.ActiveGreen,
                        fontSize = 11.5.sp,
                        lineHeight = 15.5.sp
                    )
                }
            }
        }

        // Section 3: Diagnostics & Recent Logs
        SectionHeader(strings.sectionDiagnostics)
        SettingsGroupCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        logsSnapshot = ModuleLogger.recentLogs()
                        showLogsDialog = true
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = YtmColors.CrimsonSoft,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.size(5.dp))
                    Text(strings.viewLogsBtn, color = Color.White, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        val diag = FeedbackMailFormatter.buildDiagnosticsText(
                            device = deviceSnapshot,
                            settings = settings,
                            recentLogs = ModuleLogger.recentLogs()
                        )
                        copyToClipboard("HyperLyricsMM Diagnostics", diag)
                        noticeText = strings.diagCopiedMsg
                        noticeIsWarning = false
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        tint = YtmColors.CrimsonSoft,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.size(5.dp))
                    Text(strings.copyDiagBtn, color = Color.White, fontSize = 12.sp)
                }
            }
        }

        // Section 4: LSPosed Setup Guide
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

        // Section 5: Acknowledgements & Inspired Projects
        SectionHeader(strings.sectionCredits)
        SettingsGroupCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = YtmColors.CrimsonSoft,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = strings.creditsBody,
                    color = YtmColors.TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp
                )
            }
        }

        // Section 6: About & License Card
        SectionHeader(strings.sectionAbout)
        SettingsGroupCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "HyperLyricsMM v${BuildConfig.VERSION_NAME}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = strings.aboutDeveloperLabel,
                    color = YtmColors.TextSecondary,
                    fontSize = 12.sp
                )
                Text(
                    text = strings.aboutLicenseLabel,
                    color = YtmColors.TextMuted,
                    fontSize = 11.5.sp
                )
                OutlinedButton(
                    onClick = { openExternalUrl(ModuleConfig.GITHUB_REPO_URL) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Code,
                        contentDescription = null,
                        tint = YtmColors.CrimsonSoft,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(strings.aboutGithubBtn, color = Color.White, fontSize = 12.5.sp)
                    Spacer(modifier = Modifier.size(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        tint = YtmColors.TextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }

    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            containerColor = YtmColors.SurfaceCard,
            title = {
                Text(
                    text = strings.logsDialogTitle,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0A0A0D))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        text = if (logsSnapshot.isEmpty()) {
                            strings.logsEmptyMsg
                        } else {
                            logsSnapshot.joinToString("\n")
                        },
                        color = Color(0xFFD6D6E0),
                        fontSize = 10.5.sp,
                        lineHeight = 14.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogsDialog = false }) {
                    Text(strings.closeBtn, color = YtmColors.CrimsonSoft)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        ModuleLogger.clearLogs()
                        logsSnapshot = emptyList()
                    }
                ) {
                    Text(strings.clearLogsBtn, color = YtmColors.TextSecondary)
                }
            }
        )
    }
}
