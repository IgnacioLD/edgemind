// SPDX-License-Identifier: GPL-3.0-or-later
package com.vela.assistant.presentation.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vela.assistant.data.system.AssistantNotificationListener

// First-run flow. State machine with four steps: Welcome → Mic → NotificationListener →
// OptionalPerms → done. Mic is described as required (the explanation says voice features
// won't work without it) but we don't hard-block; UX research consistently shows that
// blocking onboarding makes users uninstall instead of grant. The user can still progress.
//
// No ViewModel — the only persistent state is the "complete" flag, owned by
// OnboardingPreferences and committed via the onComplete callback. Step-local state stays in
// remember{} because it's cheap to recompute and dies with the screen.
private enum class Step { Welcome, Mic, NotificationListener, OptionalPerms }

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    var step by remember { mutableStateOf(Step.Welcome) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        when (step) {
            Step.Welcome -> WelcomeStep(onContinue = { step = Step.Mic })
            Step.Mic -> MicPermissionStep(
                onContinue = { step = Step.NotificationListener },
            )
            Step.NotificationListener -> NotificationListenerStep(
                onContinue = { step = Step.OptionalPerms },
                onSkip = { step = Step.OptionalPerms },
            )
            Step.OptionalPerms -> OptionalPermissionsStep(
                onDone = onComplete,
                onSkip = onComplete,
            )
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Vela",
            style = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Your private AI assistant. Everything stays on your device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(48.dp))
        WelcomeBullet(
            icon = Icons.Outlined.Lock,
            text = "No data sent to servers",
        )
        Spacer(modifier = Modifier.height(20.dp))
        WelcomeBullet(
            icon = Icons.Outlined.Bolt,
            text = "Powered by Gemma 4 on-device",
        )
        Spacer(modifier = Modifier.height(20.dp))
        WelcomeBullet(
            icon = Icons.Outlined.Build,
            text = "Controls your phone with voice or text",
        )
        Spacer(modifier = Modifier.height(64.dp))
        PrimaryButton(text = "Get started", onClick = onContinue)
    }
}

@Composable
private fun WelcomeBullet(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun MicPermissionStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    var requested by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        requested = true
    }
    PermissionStepLayout(
        icon = Icons.Outlined.Mic,
        title = "Microphone",
        body = "Vela needs your microphone so you can speak instead of type. Audio goes straight into the model on this device — nothing is uploaded.",
        primaryLabel = if (granted) "Continue" else "Allow microphone",
        primaryEnabled = true,
        onPrimary = {
            if (granted) onContinue() else launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        secondaryLabel = if (requested && !granted) "Continue without mic" else null,
        onSecondary = onContinue,
        footnote = if (requested && !granted) {
            "Voice features won't work until you enable this in Settings."
        } else null,
    )
}

@Composable
private fun NotificationListenerStep(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    PermissionStepLayout(
        icon = Icons.Outlined.Notifications,
        title = "Notification access",
        body = "Vela uses notification access to read what music is playing and to control your media app from voice commands. This is optional — Vela still works without it.",
        primaryLabel = if (AssistantNotificationListener.isEnabled(context)) "Continue" else "Open settings",
        primaryEnabled = true,
        onPrimary = {
            if (AssistantNotificationListener.isEnabled(context)) {
                onContinue()
            } else {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { context.startActivity(intent) }
            }
        },
        secondaryLabel = "Skip for now",
        onSecondary = onSkip,
        footnote = null,
    )
}

@Composable
private fun OptionalPermissionsStep(
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> onDone() }
    PermissionStepLayout(
        icon = Icons.Outlined.CalendarMonth,
        title = "Calendar & Contacts",
        body = "Optional. Lets Vela read or create calendar events and look up contacts when you ask. You can grant these later from Settings.",
        primaryLabel = "Grant access",
        primaryEnabled = true,
        onPrimary = {
            launcher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.READ_CONTACTS,
                ),
            )
        },
        secondaryLabel = "Skip",
        onSecondary = onSkip,
        footnote = null,
    )
}

@Composable
private fun PermissionStepLayout(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    onSecondary: () -> Unit,
    footnote: String?,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (footnote != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = footnote,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(48.dp))
        PrimaryButton(text = primaryLabel, onClick = onPrimary, enabled = primaryEnabled)
        if (secondaryLabel != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
