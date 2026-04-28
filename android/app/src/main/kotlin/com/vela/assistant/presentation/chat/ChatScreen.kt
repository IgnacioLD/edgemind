package com.vela.assistant.presentation.chat

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.assistant.R
import com.vela.assistant.domain.model.Message
import com.vela.assistant.domain.model.MessageRole
import com.vela.assistant.domain.model.ModelStatus
import com.vela.assistant.presentation.common.theme.StatusError
import com.vela.assistant.presentation.common.theme.StatusMuted
import com.vela.assistant.presentation.common.theme.StatusPreparing
import com.vela.assistant.presentation.common.theme.StatusReady
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var settingsOpen by remember { mutableStateOf(false) }

    // One-shot system notices (e.g. KV-cache auto-reset) surface as a transient Snackbar.
    LaunchedEffect(Unit) {
        viewModel.systemNotices.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startRecording()
    }
    val toolPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* tools re-check on next call */ }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            EdgeTopBar(
                modelStatus = uiState.modelStatus,
                isPreparing = uiState.isPreparing,
                isLoading = uiState.isLoading,
                onNewConversation = viewModel::newConversation,
                onSettingsClick = { settingsOpen = true },
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                snackbar = { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
    ) { paddingValues ->
        if (settingsOpen) {
            ToolPermissionsDialog(
                onGrantToolPermissions = {
                    toolPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR,
                            Manifest.permission.READ_CONTACTS,
                        ),
                    )
                },
                onDismiss = { settingsOpen = false },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            uiState.error?.let { error ->
                ErrorBanner(message = error, onDismiss = { viewModel.clearError() })
            }

            when (val status = uiState.modelStatus) {
                ModelStatus.Missing,
                is ModelStatus.Failed,
                is ModelStatus.Downloading -> ModelGate(
                    status = status,
                    onDownload = viewModel::downloadModel,
                    onCancel = viewModel::cancelDownload,
                )
                ModelStatus.Ready -> if (uiState.isPreparing && uiState.messages.isEmpty()) {
                    PreparingModel()
                } else {
                    ChatBody(
                        uiState = uiState,
                        listState = listState,
                        onSendMessage = { text -> viewModel.sendMessage(text) },
                        onStopGeneration = viewModel::stopGeneration,
                        onMicPress = {
                            if (viewModel.hasMicPermission()) {
                                viewModel.startRecording()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onMicRelease = viewModel::stopRecording,
                    )
                }
            }
        }
    }
}

// Transparent top bar with the brand wordmark and a status dot. No heavy colored block —
// the deep background flows under the status bar.
@Composable
private fun EdgeTopBar(
    modelStatus: ModelStatus,
    isPreparing: Boolean,
    isLoading: Boolean,
    onNewConversation: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val statusColor = when {
        modelStatus is ModelStatus.Failed -> StatusError
        modelStatus is ModelStatus.Downloading || isPreparing -> StatusPreparing
        modelStatus == ModelStatus.Ready -> StatusReady
        else -> StatusMuted
    }
    val statusLabel = when {
        modelStatus is ModelStatus.Failed -> "Offline"
        modelStatus is ModelStatus.Downloading -> "Downloading"
        isPreparing -> "Preparing"
        isLoading -> "Thinking"
        modelStatus == ModelStatus.Ready -> "Ready"
        else -> "Idle"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Vela",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = statusColor, animatePulse = isLoading || isPreparing)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onNewConversation) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New conversation",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color, animatePulse: Boolean) {
    val transition = rememberInfiniteTransition(label = "status-dot")
    val pulse by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "status-dot-pulse",
    )
    val alpha = if (animatePulse) pulse else 1f
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color = color.copy(alpha = alpha), shape = CircleShape),
    )
}

@Composable
private fun ChatBody(
    uiState: ChatUiState,
    listState: LazyListState,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onMicPress: () -> Unit,
    onMicRelease: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.messages.isEmpty() && !uiState.isLoading) {
            WelcomeEmptyState(
                modifier = Modifier.weight(1f),
                onSuggestionClick = onSendMessage,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.messages) { message ->
                    MessageBubble(message = message)
                }
                if (uiState.isLoading) {
                    item { LoadingIndicator() }
                }
            }
        }
        if (uiState.isRecording) {
            RecordingIndicator()
        }
        MessageInputField(
            onSendMessage = onSendMessage,
            onStopGeneration = onStopGeneration,
            onMicPress = onMicPress,
            onMicRelease = onMicRelease,
            enabled = !uiState.isLoading && !uiState.isRecording,
            isGenerating = uiState.isLoading,
            isRecording = uiState.isRecording,
        )
    }
}

@Composable
private fun WelcomeEmptyState(
    modifier: Modifier = Modifier,
    onSuggestionClick: (String) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Brand "halo" — soft gradient orb behind the wordmark.
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.0f),
                        ),
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Hi, I'm Vela",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Private, on-device. Ask anything or hold the mic.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))
        // Localized suggestion chips. Strings live in res/values/strings.xml (English) and
        // res/values-es/strings.xml (Spanish); each chip showcases a registered tool.
        val suggestions = listOf(
            stringResource(R.string.suggestion_timer),
            stringResource(R.string.suggestion_calendar),
            stringResource(R.string.suggestion_alarm),
            stringResource(R.string.suggestion_now_playing),
        )
        suggestions.forEach { prompt ->
            SuggestionChip(text = prompt, onClick = { onSuggestionClick(prompt) })
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ModelGate(
    status: ModelStatus,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Gemma 4 (E2B)",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Vela needs a 2.58 GB model to run on your device. Wi-Fi recommended.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))

        when (status) {
            ModelStatus.Missing -> {
                Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text("Download model") }
            }
            is ModelStatus.Downloading -> {
                val total = status.bytesTotal.takeIf { it > 0 } ?: 1L
                val fraction = (status.bytesDone.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${formatMb(status.bytesDone)} of ${formatMb(status.bytesTotal)} (${(fraction * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
            is ModelStatus.Failed -> {
                Text(
                    text = "Download failed: ${status.message}",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onDownload) { Text("Retry") }
            }
            ModelStatus.Ready -> Unit
        }
    }
}

@Composable
private fun PreparingModel() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Preparing Gemma 4…",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "First run can take up to a minute while the model loads into memory.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatMb(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000) "%.2f GB".format(mb / 1000) else "%.0f MB".format(mb)
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }
    val isVoice = message.voiceWaveform != null && message.voiceDurationMs != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(color = containerColor, shape = bubbleShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (isVoice) {
                VoiceBubbleContent(
                    waveform = message.voiceWaveform!!,
                    durationMs = message.voiceDurationMs!!,
                    barColor = contentColor,
                )
            } else {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
        }
    }
}

@Composable
private fun VoiceBubbleContent(
    waveform: List<Float>,
    durationMs: Long,
    barColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = barColor,
            modifier = Modifier.size(18.dp),
        )
        WaveformBars(
            amplitudes = waveform,
            color = barColor,
            modifier = Modifier
                .height(28.dp)
                .width(160.dp),
        )
        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = barColor,
        )
    }
}

@Composable
private fun WaveformBars(
    amplitudes: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (amplitudes.isEmpty()) return@Canvas
        val n = amplitudes.size
        val totalGapWidth = (n - 1) * 3f
        val barWidth = ((size.width - totalGapWidth) / n).coerceAtLeast(1f)
        val minBarHeight = 4f
        val maxBarHeight = size.height
        for (i in 0 until n) {
            val amp = amplitudes[i].coerceIn(0f, 1f)
            val barHeight = (minBarHeight + (maxBarHeight - minBarHeight) * amp)
                .coerceAtLeast(minBarHeight)
            val x = i * (barWidth + 3f)
            val y = (size.height - barHeight) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%d:%02d".format(mins, secs)
}

@Composable
private fun MessageInputField(
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onMicPress: () -> Unit,
    onMicRelease: () -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    isRecording: Boolean,
) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    "Ask anything…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            enabled = enabled,
            maxLines = 4,
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        when {
            isGenerating -> ActionCircleButton(
                icon = Icons.Default.Stop,
                description = "Stop generation",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                onClick = onStopGeneration,
            )
            text.isNotBlank() -> ActionCircleButton(
                icon = Icons.Default.Send,
                description = "Send",
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = {
                    onSendMessage(text)
                    text = ""
                },
            )
            else -> BreathingMicButton(
                onPress = onMicPress,
                onRelease = onMicRelease,
                enabled = enabled || isRecording,
                isRecording = isRecording,
            )
        }
    }
}

// Reusable circular action affordance — used for both Send and Stop. Keeps geometry consistent
// with the mic button so the composition height never jitters as state flips.
@Composable
private fun ActionCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(color = container, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = content)
    }
}

// Mic button that "breathes" gently when idle (alpha + scale of a halo ring) and pulses harder
// when recording. Push-to-talk via pointerInput; touch-down starts capture, release ends it.
@Composable
private fun BreathingMicButton(
    onPress: () -> Unit,
    onRelease: () -> Unit,
    enabled: Boolean,
    isRecording: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "mic-breathing")
    val haloScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = if (isRecording) 1.35f else 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isRecording) 700 else 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic-halo-scale",
    )
    val haloAlpha by transition.animateFloat(
        initialValue = 0.0f,
        targetValue = if (isRecording) 0.55f else 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isRecording) 700 else 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic-halo-alpha",
    )

    val container = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val content = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Halo ring — purely decorative, sits behind the button.
        Box(
            modifier = Modifier
                .size(60.dp)
                .scale(haloScale)
                .background(color = container.copy(alpha = haloAlpha), shape = CircleShape),
        )
        // Actual button — fixed size on top of the halo.
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(color = container, shape = CircleShape)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            onPress()
                            try {
                                tryAwaitRelease()
                            } finally {
                                onRelease()
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Mic, contentDescription = "Hold to record", tint = content)
        }
    }
}

@Composable
private fun RecordingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(color = MaterialTheme.colorScheme.error, animatePulse = true)
        Text(
            text = "Listening… release to send",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun LoadingIndicator() {
    val bubbleShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, shape = bubbleShape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypingDot(delayMs = 0)
            TypingDot(delayMs = 150)
            TypingDot(delayMs = 300)
        }
    }
}

@Composable
private fun TypingDot(delayMs: Int) {
    val transition = rememberInfiniteTransition(label = "typing-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = delayMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "typing-dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                shape = CircleShape,
            ),
    )
}

@Composable
private fun ToolPermissionsDialog(
    onGrantToolPermissions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Text(
                "Permissions & Roles",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        },
        text = {
            Column {
                Text(
                    "Vela needs a few grants to act as your assistant.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "• Calendar & Contacts — read/create events, look up contacts.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "• Notification access — see what's playing for music control.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "• Default Assistant — long-press home / assist gesture launches Vela.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = {
                    onGrantToolPermissions()
                    onDismiss()
                }) { Text("Grant calendar & contacts") }
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    runCatching { context.startActivity(intent) }
                    onDismiss()
                }) { Text("Open notification access") }
                TextButton(onClick = {
                    requestAssistantRole(context)
                    onDismiss()
                }) { Text("Set as default Assistant") }
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    runCatching { context.startActivity(intent) }
                    onDismiss()
                }) { Text("Open app settings") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun requestAssistantRole(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(android.app.role.RoleManager::class.java)
        if (rm?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true) {
            val intent = rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT).apply {
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

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
