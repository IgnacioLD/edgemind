// SPDX-License-Identifier: GPL-3.0-or-later
package com.vela.assistant.presentation.chat

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.vela.assistant.BuildConfig
import com.vela.assistant.domain.model.ModelStatus
import com.vela.assistant.presentation.common.theme.StatusError
import com.vela.assistant.presentation.common.theme.StatusMuted
import com.vela.assistant.presentation.common.theme.StatusReady

// Replaces the previous Permissions & Roles AlertDialog. A bottom sheet with grouped sections
// scans better, has room for status pills next to each row, and pulls live state (permission
// grants, assistant role, notification-listener bind) from Android each time the user
// re-opens it.
//
// Each row that opens a system Settings page is fire-and-forget: we don't try to detect the
// user's choice on return. Instead, when the sheet resumes (Lifecycle.RESUMED) we re-query
// every state pill. That keeps the code free of result-launchers for grants Android delivers
// out-of-band anyway.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    modelStatus: ModelStatus,
    activeBackend: String,
    messageCount: Int,
    onRequestToolPermissions: () -> Unit,
    onResetConversation: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onRunBenchmark: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Compose's `LocalLifecycleOwner` was moved to androidx.lifecycle.compose in newer
    // versions; the platform one still works on the lifecycle 2.6.x we're pinned to. The
    // deprecation warning is cosmetic — both resolve to the same provider via context.
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Permission/role/listener pills are queried imperatively against Context. We refresh them
    // whenever the host activity resumes — i.e. when the user returns from a Settings page they
    // opened from this sheet.
    var refreshTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val calendarGranted = remember(refreshTick) { isPermissionGranted(context, android.Manifest.permission.READ_CALENDAR) }
    val contactsGranted = remember(refreshTick) { isPermissionGranted(context, android.Manifest.permission.READ_CONTACTS) }
    val notificationListenerEnabled = remember(refreshTick) { isNotificationListenerEnabled(context) }
    val isDefaultAssistant = remember(refreshTick) { isDefaultAssistant(context) }
    val obsidianGranted = remember(refreshTick) { hasAllFilesAccess() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )

            SectionHeader("Status")
            SettingsCard {
                StatusRow(
                    icon = Icons.Outlined.Memory,
                    title = "Model",
                    subtitle = modelStatusSubtitle(modelStatus, activeBackend),
                    pill = modelStatusPill(modelStatus),
                )
                Divider()
                StatusRow(
                    icon = Icons.Filled.Refresh,
                    title = "Conversation",
                    subtitle = if (messageCount == 0) "No messages yet" else "$messageCount messages • auto-resets every 20 turns",
                    pill = null,
                )
            }

            SectionHeader("Permissions")
            SettingsCard {
                ActionRow(
                    icon = Icons.Filled.CalendarMonth,
                    title = "Calendar",
                    subtitle = "Read upcoming events, create new ones",
                    pill = if (calendarGranted) StatusPill.Granted else StatusPill.Required,
                    trailing = if (calendarGranted) TrailingIcon.OpenExternal else TrailingIcon.Chevron,
                    onClick = if (calendarGranted) ({ openAppDetails(context) }) else onRequestToolPermissions,
                )
                Divider()
                ActionRow(
                    icon = Icons.Filled.People,
                    title = "Contacts",
                    subtitle = "Look up phone numbers and emails",
                    pill = if (contactsGranted) StatusPill.Granted else StatusPill.Required,
                    trailing = if (contactsGranted) TrailingIcon.OpenExternal else TrailingIcon.Chevron,
                    onClick = if (contactsGranted) ({ openAppDetails(context) }) else onRequestToolPermissions,
                )
                Divider()
                ActionRow(
                    icon = Icons.Filled.MusicNote,
                    title = "Music control",
                    subtitle = "Notification access — see what's playing",
                    pill = if (notificationListenerEnabled) StatusPill.Granted else StatusPill.Required,
                    trailing = TrailingIcon.OpenExternal,
                    onClick = { openNotificationListenerSettings(context) },
                )
                Divider()
                ActionRow(
                    icon = Icons.Filled.Mic,
                    title = "Default assistant",
                    subtitle = "Long-press home / assist gesture launches Vela",
                    pill = if (isDefaultAssistant) StatusPill.Active else StatusPill.Optional,
                    trailing = TrailingIcon.OpenExternal,
                    onClick = { requestAssistantRole(context) },
                )
                Divider()
                ActionRow(
                    icon = Icons.Filled.Folder,
                    title = "Obsidian vault",
                    subtitle = "Read & write notes at /sdcard/Obsidian",
                    pill = if (obsidianGranted) StatusPill.Granted else StatusPill.Required,
                    trailing = TrailingIcon.OpenExternal,
                    onClick = { openAllFilesAccessSettings(context) },
                )
            }

            SectionHeader("Conversation")
            SettingsCard {
                ActionRow(
                    icon = Icons.Filled.Refresh,
                    title = "Reset conversation",
                    subtitle = "Wipe history and start a fresh KV cache",
                    pill = null,
                    trailing = TrailingIcon.Chevron,
                    onClick = {
                        onResetConversation()
                        onDismiss()
                    },
                )
                Divider()
                ActionRow(
                    icon = Icons.Filled.School,
                    title = "Replay onboarding",
                    subtitle = "Walk through the first-run permission flow again",
                    pill = null,
                    trailing = TrailingIcon.Chevron,
                    onClick = {
                        onReplayOnboarding()
                        onDismiss()
                    },
                )
            }

            SectionHeader("Developer")
            SettingsCard {
                ActionRow(
                    icon = Icons.Filled.Speed,
                    title = "Run benchmark",
                    subtitle = "Logs latency stats under tag 'VelaBenchmark'",
                    pill = null,
                    trailing = TrailingIcon.Chevron,
                    onClick = {
                        onRunBenchmark()
                        onDismiss()
                    },
                )
                Divider()
                ActionRow(
                    icon = Icons.Filled.Notifications,
                    title = "App settings",
                    subtitle = "Open Android's permission and notification panel",
                    pill = null,
                    trailing = TrailingIcon.OpenExternal,
                    onClick = { openAppDetails(context) },
                )
            }

            SectionHeader("About")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Vela",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Private, on-device. No cloud, no telemetry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp, start = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        Column { content() }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    pill: StatusPill?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pill != null) PillChip(pill)
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    pill: StatusPill?,
    trailing: TrailingIcon,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pill != null) {
            PillChip(pill)
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = when (trailing) {
                TrailingIcon.Chevron -> Icons.Filled.ChevronRight
                TrailingIcon.OpenExternal -> Icons.AutoMirrored.Filled.OpenInNew
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PillChip(pill: StatusPill) {
    val (label, fg, bg) = when (pill) {
        StatusPill.Granted -> Triple("Granted", StatusReady, StatusReady.copy(alpha = 0.15f))
        StatusPill.Active -> Triple("Active", StatusReady, StatusReady.copy(alpha = 0.15f))
        StatusPill.Required -> Triple("Required", StatusError, StatusError.copy(alpha = 0.15f))
        StatusPill.Optional -> Triple("Optional", StatusMuted, StatusMuted.copy(alpha = 0.15f))
        StatusPill.Loading -> Triple("Loading", StatusMuted, StatusMuted.copy(alpha = 0.15f))
        StatusPill.Failed -> Triple("Failed", StatusError, StatusError.copy(alpha = 0.15f))
        StatusPill.Missing -> Triple("Missing", StatusError, StatusError.copy(alpha = 0.15f))
    }
    Box(
        modifier = Modifier
            .background(color = bg, shape = RoundedCornerShape(percent = 50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
        )
    }
}

private enum class StatusPill { Granted, Active, Required, Optional, Loading, Failed, Missing }

private enum class TrailingIcon { Chevron, OpenExternal }

private fun modelStatusPill(status: ModelStatus): StatusPill = when (status) {
    is ModelStatus.Ready -> StatusPill.Granted
    is ModelStatus.Downloading -> StatusPill.Loading
    is ModelStatus.Failed -> StatusPill.Failed
    ModelStatus.Missing -> StatusPill.Missing
}

private fun modelStatusSubtitle(status: ModelStatus, activeBackend: String): String = when (status) {
    is ModelStatus.Ready -> "Gemma 4 E2B • running on ${activeBackend.uppercase()}"
    is ModelStatus.Downloading -> {
        val mb = status.bytesDone / 1_000_000
        val totalMb = (status.bytesTotal / 1_000_000).coerceAtLeast(1)
        "Downloading ${mb}/${totalMb} MB"
    }
    is ModelStatus.Failed -> status.message
    ModelStatus.Missing -> "Not downloaded yet"
}

// ── Imperative platform queries ─────────────────────────────────────────────────────────────

private fun isPermissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

// Notification-listener bind state isn't exposed via PackageManager. The supported check is to
// scan Settings.Secure.ENABLED_NOTIFICATION_LISTENERS for our component's flattened name —
// that's what the platform itself does when deciding whether to bind the listener.
private fun isNotificationListenerEnabled(context: Context): Boolean {
    val packageName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return flat.split(":").any { it.startsWith("$packageName/") }
}

private fun isDefaultAssistant(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val rm = context.getSystemService(RoleManager::class.java) ?: return false
    return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
}

private fun openNotificationListenerSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
    runCatching { context.startActivity(intent) }
}

private fun openAppDetails(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
}

// MANAGE_EXTERNAL_STORAGE — broad file access required to read/write the Obsidian vault.
// On API < 30 the legacy storage permissions still work and the dedicated settings page
// doesn't exist; falling back to the app details panel covers both branches.
private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

private fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }.onSuccess { return }
        // Some OEM-customized Android 11+ builds gate the per-app variant — the global page
        // still works.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
            )
        }
        return
    }
    openAppDetails(context)
}

private fun requestAssistantRole(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(RoleManager::class.java)
        if (rm?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
            val intent = rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            runCatching { context.startActivity(intent) }
            return
        }
    }
    val fallback = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(fallback) }
}
