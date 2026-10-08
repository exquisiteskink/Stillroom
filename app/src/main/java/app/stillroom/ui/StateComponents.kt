package app.stillroom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

const val DisconnectedMessage = "Connect an account to see your household."

@Composable
private fun StateMessage(
    title: String,
    message: String,
    modifier: Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    action: (() -> Unit)? = null,
    indicator: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        indicator()
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && actionLabel != null) {
            PrimaryButton(onClick = action, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, reducedMotion: Boolean = LocalReducedMotion.current, message: String = "Loading records…") {
    StateMessage("Loading", message, modifier.testTag("state-loading"), indicator = {
        if (!reducedMotion) CircularProgressIndicator(modifier = Modifier.testTag("loading-indicator"))
    })
}

@Composable
fun EmptyState(modifier: Modifier = Modifier, message: String = DisconnectedMessage, onConnect: (() -> Unit)? = null) {
    StateMessage("Connect to Grocy", message, modifier.testTag("state-empty"), Icons.Outlined.Inbox, "Connect", onConnect)
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    StateMessage("Could not load records", message, modifier.testTag("state-error"),
        Icons.Outlined.ErrorOutline, "Reload", onRetry)
}

@Composable
fun OfflineState(modifier: Modifier = Modifier) {
    StateMessage("Offline", "Showing saved data. Changes may need review when you reconnect.",
        modifier.testTag("state-offline"), Icons.Outlined.CloudOff)
}

@Composable
fun PermissionDeniedState(modifier: Modifier = Modifier) {
    StateMessage("Access unavailable", "This account does not have permission to view this section.",
        modifier.testTag("state-permission-denied"), Icons.Outlined.Lock)
}
