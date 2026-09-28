package com.jsalinas.grokwatch.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.jsalinas.grokwatch.GrokWatchApp

@Composable
fun HistoryScreen(onOpen: (Long) -> Unit, onNewChat: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { (context.applicationContext as GrokWatchApp).repository }
    val conversations by remember { repo.observeConversations() }.collectAsState(initial = null)
    val listState = rememberScalingLazyListState()

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onNewChat, buttonSize = EdgeButtonSize.Small) { Text("New chat") }
        },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { ListHeader { Text("History") } }
            val list = conversations
            when {
                list == null -> item { Text("Loading…") }
                list.isEmpty() -> item {
                    Text(
                        "No conversations yet.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> items(list, key = { it.id }) { conv ->
                    FilledTonalButton(
                        onClick = { onOpen(conv.id) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(conv.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            Text(
                                DateUtils.getRelativeTimeSpanString(conv.updatedAt).toString(),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }
    }
}
