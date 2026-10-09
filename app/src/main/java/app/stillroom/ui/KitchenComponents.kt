package app.stillroom.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KitchenRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing && !LocalReducedMotion.current, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        content()
        if (refreshing) {
            if (LocalReducedMotion.current) {
                Text("Refreshing…", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.TopCenter).background(MaterialTheme.colorScheme.surface).padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            } else LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

@Composable
fun KitchenList(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    spacedBy: Dp = 16.dp,
    content: LazyListScope.() -> Unit,
) {
    KitchenRefreshBox(refreshing, onRefresh, modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(spacedBy),
            content = content,
        )
    }
}

@Composable
fun KitchenGrid(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    columns: GridCells = GridCells.Adaptive(168.dp),
    content: LazyGridScope.() -> Unit,
) {
    KitchenRefreshBox(refreshing, onRefresh, modifier) {
        LazyVerticalGrid(
            columns = columns,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

fun LazyGridScope.kitchenHeader(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }, content = { content() })
}

@Composable
fun KitchenCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, elevation = elevation, content = content)
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, elevation = elevation, content = content)
    }
}

@Composable
fun KitchenCheckRow(
    name: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    due: String? = null,
    tone: ColorTone = ColorTone.Due,
    detail: String? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    waiting: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 64.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).sizeIn(minHeight = 48.dp)
                    .toggleable(value = checked && !waiting, enabled = enabled && !waiting,
                        role = Role.Checkbox, onValueChange = onCheckedChange)
                    .semantics(mergeDescendants = true) {
                        if (waiting) stateDescription = "Waiting to sync"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = checked && !waiting, onCheckedChange = null, enabled = enabled && !waiting)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    if (!due.isNullOrBlank()) QuantityBadge(due, tone)
                    if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (waiting) KitchenWhisper("Waiting to sync")
                }
            }
            trailing?.invoke()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun KitchenStockRow(
    name: String,
    amount: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    due: String? = null,
    tone: ColorTone = ColorTone.Neutral,
    /** Extra lines chosen in Settings → Stock → Shown details. Empty keeps the row exactly as before. */
    details: List<String> = emptyList(),
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                if (!due.isNullOrBlank()) QuantityBadge(due, tone)
                details.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (amount.isNotBlank()) {
                Text(
                    amount,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 72.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun KitchenSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
fun KitchenWhisper(text: String?, modifier: Modifier = Modifier) {
    if (!text.isNullOrBlank()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
fun KitchenError(text: String?, modifier: Modifier = Modifier) {
    if (!text.isNullOrBlank()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
fun KitchenEmpty(
    title: String,
    message: String,
    @DrawableRes illustration: Int = 0,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (illustration != 0) {
            Image(painterResource(illustration), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(168.dp).clip(MaterialTheme.shapes.large))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RecipeTile(
    name: String,
    picture: ByteArray?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberDownsampledImage(picture, TILE_DECODED_PIXELS)
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        }
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
        )
    }
}

@Composable
fun DashboardRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 56.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun QuantityBadge(text: String, tone: ColorTone = ColorTone.Neutral, modifier: Modifier = Modifier) {
    val kitchen = LocalKitchen.current
    val color = when (tone) {
        ColorTone.Due -> kitchen.due
        ColorTone.Overdue -> kitchen.overdue
        ColorTone.Expiring -> kitchen.expiring
        ColorTone.Stocked -> kitchen.stocked
        ColorTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = modifier,
    )
}

enum class ColorTone { Neutral, Due, Overdue, Expiring, Stocked }

@Composable
fun SyncSpinner(busy: Boolean, modifier: Modifier = Modifier) {
    if (busy) {
        if (LocalReducedMotion.current) Text("Syncing…", style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite })
        else CircularProgressIndicator(modifier.size(24.dp).semantics { stateDescription = "Syncing" }, strokeWidth = 2.dp)
    }
}

