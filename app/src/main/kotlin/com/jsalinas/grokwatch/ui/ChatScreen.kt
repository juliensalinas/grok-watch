package com.jsalinas.grokwatch.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text

@Composable
fun ChatScreen(conversationId: Long, onOpenHistory: () -> Unit, onNewChat: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ChatViewModel = viewModel(
        key = "chat-$conversationId",
        factory = viewModelFactory { initializer { ChatViewModel(app, conversationId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // --- microphone permission: ask on first run, then auto-listen on every start ---
    var micGranted by remember { mutableStateOf(context.hasMicPermission()) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
        askedOnce = true
    }
    LaunchedEffect(Unit) {
        if (!micGranted && !askedOnce) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // --- session lifecycle: listen while visible, stop mic + socket in onStop ---
    LifecycleStartEffect(micGranted, vm) {
        micGranted = context.hasMicPermission()
        if (micGranted) vm.start()
        onStopOrDispose { vm.stop() }
    }

    // --- keep the screen on during a live session ---
    val sessionLive = state.status != SessionStatus.IDLE && state.status != SessionStatus.ERROR
    val activity = context.findActivity()
    DisposableEffect(sessionLive, activity) {
        if (sessionLive) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val messages = state.messages
    val firstMessageIndex = 1

    // Auto-scroll to the newest text as it streams in (unless the user is scrolling).
    val lastText = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastText, state.error) {
        if (listState.isScrollInProgress) return@LaunchedEffect
        val target = if (state.error != null) firstMessageIndex + messages.size
        else if (messages.isNotEmpty()) firstMessageIndex + messages.lastIndex else 0
        listState.scrollToItem(target)
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        val viewport = listState.layoutInfo.viewportSize.height
        if (info != null && viewport > 0) {
            val excess = info.size - viewport / 3
            if (excess > 0) listState.scrollToItem(target, excess)
        }
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            when {
                state.status == SessionStatus.SPEAKING ->
                    EdgeButton(onClick = vm::interrupt, buttonSize = EdgeButtonSize.Small) { Text("Stop") }
                state.error != null && state.errorRetryable && micGranted ->
                    EdgeButton(onClick = vm::retry, buttonSize = EdgeButtonSize.Small) { Text("Retry") }
                else ->
                    EdgeButton(onClick = onOpenHistory, buttonSize = EdgeButtonSize.Small) { Text("History") }
            }
        },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            anchorType = ScalingLazyListAnchorType.ItemStart,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { StatusHeader(state, micGranted) }

            items(messages, key = { it.localId }) { msg -> MessageBubble(msg) }

            if (!micGranted) {
                item {
                    ErrorCard(
                        text = "Grok needs the microphone to hear you.",
                        actionLabel = if (askedOnce) "Grant / Settings" else "Grant",
                        onAction = {
                            val activity2 = context.findActivity()
                            if (askedOnce && activity2 != null &&
                                !activity2.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                            ) {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", context.packageName, null)),
                                )
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    )
                }
            }

            state.error?.let { err ->
                item {
                    ErrorCard(
                        text = err,
                        actionLabel = if (state.errorRetryable) "Retry" else null,
                        onAction = vm::retry,
                    )
                }
            }

            item { Spacer(Modifier.size(4.dp)) }
            item {
                FilledTonalButton(
                    onClick = onNewChat,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New chat") },
                )
            }
            item {
                SwitchButton(
                    checked = state.voiceBargeIn,
                    onCheckedChange = vm::setVoiceBargeIn,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Voice interrupt") },
                    secondaryLabel = { Text(if (state.voiceBargeIn) "Talk over Grok" else "Tap Stop instead") },
                )
            }
        }
    }
}

@Composable
private fun StatusHeader(state: ChatUiState, micGranted: Boolean) {
    val (label, color) = when {
        !micGranted -> "Mic permission needed" to MaterialTheme.colorScheme.error
        state.apiKeyMissing -> "API key missing" to MaterialTheme.colorScheme.error
        else -> when (state.status) {
            SessionStatus.IDLE -> "Paused" to Color.Gray
            SessionStatus.CONNECTING -> "Connecting…" to Color(0xFFFFC107)
            SessionStatus.LISTENING -> "Listening…" to Color(0xFF4CAF50)
            SessionStatus.HEARING -> "Hearing you…" to Color(0xFF00BCD4)
            SessionStatus.THINKING -> "Thinking…" to Color(0xFFFFC107)
            SessionStatus.SPEAKING -> "Grok is speaking" to Color(0xFFB388FF)
            SessionStatus.ERROR -> "Error" to MaterialTheme.colorScheme.error
        }
    }
    val pulsing = state.status in setOf(SessionStatus.LISTENING, SessionStatus.HEARING, SessionStatus.SPEAKING, SessionStatus.CONNECTING)
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .alpha(if (pulsing) pulse else 1f)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = color)
        }
        state.title?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
        state.notice?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFC107), textAlign = TextAlign.Center)
        }
        if (state.messages.isEmpty() && state.status == SessionStatus.LISTENING) {
            Text(
                "Say something…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == Role.USER
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (isUser) 14.dp else 0.dp, end = if (isUser) 0.dp else 14.dp)
            .background(bg, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            if (isUser) "You" else "Grok",
            style = MaterialTheme.typography.labelSmall,
            color = fg.copy(alpha = 0.7f),
        )
        val shown = msg.text.ifBlank { if (isUser) "…" else "…" }
        Text(
            shown + if (!msg.final && msg.text.isNotBlank()) " ▍" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
        )
    }
}

@Composable
private fun ErrorCard(text: String, actionLabel: String?, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
        if (actionLabel != null) {
            Spacer(Modifier.size(6.dp))
            Button(onClick = onAction, label = { Text(actionLabel) })
        }
    }
}

private fun Context.hasMicPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
