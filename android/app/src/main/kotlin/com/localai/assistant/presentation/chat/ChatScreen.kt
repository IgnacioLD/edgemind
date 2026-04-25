package com.localai.assistant.presentation.chat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localai.assistant.domain.model.Message
import com.localai.assistant.domain.model.MessageRole
import com.localai.assistant.domain.model.ModelStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startRecording()
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EdgeMind") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { paddingValues ->
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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.messages) { message ->
                MessageBubble(message = message)
            }
            if (uiState.isLoading) {
                item { LoadingIndicator() }
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
private fun ModelGate(
    status: ModelStatus,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Gemma 4 (E2B)",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "EdgeMind needs a 2.58 GB model to run on your device. Wi-Fi recommended.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))

        when (status) {
            ModelStatus.Missing -> {
                Button(onClick = onDownload) { Text("Download model") }
            }
            is ModelStatus.Downloading -> {
                val total = status.bytesTotal.takeIf { it > 0 } ?: 1L
                val fraction = (status.bytesDone.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${formatMb(status.bytesDone)} of ${formatMb(status.bytesTotal)} (${(fraction * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall,
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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Preparing Gemma 4 on first run…",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "This can take 20–60 seconds while the model loads into memory.",
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ),
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
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
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask anything... or hold the mic") },
            enabled = enabled,
            maxLines = 3,
        )

        when {
            isGenerating -> {
                FilledTonalIconButton(
                    onClick = onStopGeneration,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop generation")
                }
            }
            text.isNotBlank() -> {
                FilledIconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSendMessage(text)
                            text = ""
                        }
                    },
                    enabled = enabled,
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
            else -> {
                MicButton(
                    onPress = onMicPress,
                    onRelease = onMicRelease,
                    enabled = enabled || isRecording,
                    isRecording = isRecording,
                )
            }
        }
    }
}

@Composable
private fun MicButton(
    onPress: () -> Unit,
    onRelease: () -> Unit,
    enabled: Boolean,
    isRecording: Boolean,
) {
    val container = if (isRecording) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val content = if (isRecording) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .size(48.dp)
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

@Composable
private fun RecordingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(MaterialTheme.colorScheme.error, shape = CircleShape),
        )
        Text(
            text = "Listening… release to send",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun LoadingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
