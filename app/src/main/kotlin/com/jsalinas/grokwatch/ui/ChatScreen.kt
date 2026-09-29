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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
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
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureAction
import androidx.wear.compose.material3.onehandedgesture.OneHandedGesturePriority
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration

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
    val bubbleParts = messages.flatMap { it.toBubbleParts() }
    // StatusHeader + VolumeControls precede the message items.
    val firstMessageIndex = 2
    val speaking = state.status == SessionStatus.SPEAKING

    // Auto-scroll to the newest text as it streams in (unless the user is scrolling).
    val lastText = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(bubbleParts.size, lastText, state.error) {
        if (listState.isScrollInProgress) return@LaunchedEffect
        val target = if (state.error != null) firstMessageIndex + bubbleParts.size
        else if (bubbleParts.isNotEmpty()) firstMessageIndex + bubbleParts.lastIndex else 0
        listState.scrollToItem(target)
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        val viewport = listState.layoutInfo.viewportSize.height
        if (info != null && viewport > 0) {
            val excess = info.size - viewport / 3
            if (excess > 0) listState.scrollToItem(target, excess)
        }
    }

    // Primary one-handed gesture: on Pixel Watch this is double-pinch (Wear OS 7).
    // The library no-ops on hardware that does not support the gesture framework.
    val stopGesture = rememberOneHandedGestureConfiguration(
        action = OneHandedGestureAction.Primary,
        gestureId = "grok-stop-speaking",
        priority = OneHandedGesturePriority.Clickable,
    )
    val screenModifier = Modifier
        .fillMaxSize()
        .then(
            if (speaking) {
                Modifier.oneHandedGesture(
                    gestureConfiguration = stopGesture,
                    onGestureLabel = "Stop Grok",
                    onGesture = { vm.interrupt() },
                )
            } else {
                Modifier
            },
        )
        .tapToMuteCurrentReply(enabled = speaking, onTap = vm::toggleResponseMute)

    Box(screenModifier) {
        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                // Stop is the bottom edge button while speaking (does not cover the transcript).
                when {
                    speaking ->
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
                // Default snap-fling + edge scaling clips tall bubbles mid-item; use free fling
                // and flat scaling so long transcripts stay fully scrollable/readable.
                flingBehavior = ScrollableDefaults.flingBehavior(),
                scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 1f, edgeAlpha = 1f),
                autoCentering = null,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { StatusHeader(state, micGranted) }
                item { VolumeControls(percent = state.volumePercent, onAdjust = vm::adjustVolume) }

            items(bubbleParts, key = { "${it.messageLocalId}-${it.partIndex}" }) { part ->
                MessageBubble(part)
            }

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
                    secondaryLabel = { Text(if (state.voiceBargeIn) "Talk over Grok" else "Double-pinch or Stop") },
                )
            }
        }
        }
    }
}



/**
 * Single-finger tap on the chat screen toggles mute for the current reply only.
 * Events are observed on the final pass and never consumed, so buttons, the bottom
 * Stop edge button, and scrolling keep working. A drag past touch slop is not a tap.
 */
private fun Modifier.tapToMuteCurrentReply(enabled: Boolean, onTap: () -> Unit): Modifier {
    if (!enabled) return this
    return pointerInput(onTap) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position
            val pointerId = down.id
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: return@awaitEachGesture
                val extraFinger = event.changes.any { it.id != pointerId && it.pressed }
                if (change.isConsumed || extraFinger) return@awaitEachGesture
                if ((change.position - start).getDistance() > slop) return@awaitEachGesture
                if (!change.pressed) {
                    onTap()
                    return@awaitEachGesture
                }
            }
        }
    }
}

@Composable
private fun VolumeControls(percent: Int, onAdjust: (Int) -> Unit) {
    val bars = 5
    val filled = ((percent.coerceIn(0, 100) / 100f) * bars).toInt().coerceIn(0, bars)
    val btnBg = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        VolumeStepButton(label = "−", background = btnBg, onClick = { onAdjust(-1) })
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(bars) { i ->
                    val h = (6 + i * 3).dp
                    Box(
                        Modifier
                            .width(5.dp)
                            .height(h)
                            .background(
                                if (i < filled) Color(0xFFB388FF) else Color.Gray.copy(alpha = 0.35f),
                                RoundedCornerShape(1.dp),
                            ),
                    )
                }
            }
            Text(
                "$percent%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        VolumeStepButton(label = "+", background = btnBg, onClick = { onAdjust(1) })
    }
}

@Composable
private fun VolumeStepButton(label: String, background: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .background(background, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
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
            SessionStatus.SPEAKING ->
                (if (state.responseMuted) "Muted" else "Grok is speaking") to Color(0xFFB388FF)
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
        if (state.status == SessionStatus.SPEAKING) {
            Text(
                if (state.responseMuted) "Tap to hear" else "Tap to mute",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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

/** One scrollable slice of a chat bubble (long transcripts are split for Wear list scrolling). */
private data class BubblePart(
    val messageLocalId: Long,
    val partIndex: Int,
    val role: Role,
    val text: String,
    val final: Boolean,
    val isFirst: Boolean,
    val isLast: Boolean,
)

/** Split long transcripts into short Wear-friendly chunks so the whole message is reachable by scroll. */
private fun ChatMessage.toBubbleParts(maxChars: Int = 180): List<BubblePart> {
    val chunks = chunkTranscript(text, maxChars)
    return chunks.mapIndexed { i, chunk ->
        BubblePart(
            messageLocalId = localId,
            partIndex = i,
            role = role,
            text = chunk,
            final = final,
            isFirst = i == 0,
            isLast = i == chunks.lastIndex,
        )
    }
}

private fun chunkTranscript(text: String, maxChars: Int): List<String> {
    if (text.length <= maxChars) return listOf(text)
    val parts = mutableListOf<String>()
    // Prefer paragraph / line breaks, then sentences, then words.
    val paragraphs = text.split(Regex("\n+")).filter { it.isNotBlank() }.ifEmpty { listOf(text) }
    val buffer = StringBuilder()
    fun flush() {
        val s = buffer.toString().trim()
        if (s.isNotEmpty()) parts += s
        buffer.clear()
    }
    for (para in paragraphs) {
        if (para.length <= maxChars) {
            if (buffer.isNotEmpty() && buffer.length + 1 + para.length > maxChars) flush()
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(para)
            if (buffer.length >= maxChars / 2) flush()
            continue
        }
        flush()
        var remaining = para
        while (remaining.length > maxChars) {
            val window = remaining.substring(0, maxChars)
            var cut = maxChars
            for (sep in listOf(". ", "? ", "! ", "; ", ", ", " ")) {
                val idx = window.lastIndexOf(sep)
                if (idx >= maxChars / 3) {
                    cut = idx + sep.length
                    break
                }
            }
            parts += remaining.substring(0, cut).trim()
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty()) buffer.append(remaining)
    }
    flush()
    return parts.ifEmpty { listOf(text) }
}

@Composable
private fun MessageBubble(part: BubblePart) {
    val isUser = part.role == Role.USER
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val shape = when {
        part.isFirst && part.isLast -> RoundedCornerShape(16.dp)
        part.isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
        part.isLast -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RoundedCornerShape(6.dp)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (isUser) 14.dp else 0.dp, end = if (isUser) 0.dp else 14.dp)
            .background(bg, shape)
            .padding(
                horizontal = 10.dp,
                vertical = if (part.isFirst || part.isLast) 6.dp else 2.dp,
            ),
    ) {
        if (part.isFirst) {
            Text(
                if (isUser) "You" else "Grok",
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.7f),
            )
        }
        val shown = part.text.ifBlank { "…" }
        // Wear Material3 Text defaults to Overflow.Clip — override so nothing is cut mid-bubble.
        Text(
            shown + if (part.isLast && !part.final && part.text.isNotBlank()) " ▍" else "",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            softWrap = true,
            overflow = TextOverflow.Visible,
            maxLines = Int.MAX_VALUE,
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
