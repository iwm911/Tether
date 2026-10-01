package app.tether.ui.chat.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tether.core.ChatItem
import app.tether.ui.theme.TetherTheme

/** Who a peer message is from / to, for the row's caption: name, else a readable address. */
internal fun peerLabel(item: ChatItem.Peer): String {
    val name = item.peerName?.trim()?.takeIf { it.isNotEmpty() }
        ?: item.peer?.trim()?.removePrefix("uds:")?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        ?: "another session"
    return if (item.incoming) "From $name" else "To $name"
}

/**
 * A message between sessions: an incoming one sits on the left like a quoted note from elsewhere,
 * an outgoing `SendMessage` on the right. Neither is a chat bubble, so they never read as the user.
 */
@Composable
internal fun PeerView(item: ChatItem.Peer, modifier: Modifier) {
    val c = TetherTheme.colors
    val tint = c.info
    val shape = RoundedCornerShape(16.dp)
    val label = peerLabel(item)
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (item.incoming) Arrangement.Start else Arrangement.End) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .clip(shape)
                .background(tint.copy(alpha = 0.07f))
                .border(1.dp, tint.copy(alpha = 0.22f), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = "$label: ${item.text}" },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (item.incoming) Icons.AutoMirrored.Rounded.CallReceived else Icons.AutoMirrored.Rounded.CallMade,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.padding(top = 4.dp))
            SelectionContainer {
                Text(item.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
