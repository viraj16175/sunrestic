package app.sunrestic

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val iconCache = LruCache<String, ImageBitmap>(300)

/**
 * Round app icon, loaded off the UI thread. Shows a letter avatar while loading or when the app
 * isn't installed (for example in a snapshot of an app you removed).
 */
@Composable
fun AppIcon(pkg: String, label: String, size: Dp, modifier: Modifier = Modifier) {
    val pm = LocalContext.current.packageManager
    val icon by produceState<ImageBitmap?>(iconCache.get(pkg), pkg) {
        if (value == null) {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { pm.getApplicationIcon(pkg).toBitmap(144, 144).asImageBitmap() }.getOrNull()
            }
            if (bmp != null) iconCache.put(pkg, bmp)
            value = bmp
        }
    }
    val bmp = icon
    if (bmp != null) {
        Image(bmp, null, modifier.size(size).clip(CircleShape))
    } else {
        Box(
            modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A tag on an app card: text and which colour it gets. */
data class AppTag(val text: String, val kind: Int) {
    companion object {
        const val BLUE = 0
        const val BROWN = 1
        const val RED = 2
        /** The user's own tags. */
        const val PURPLE = 3
    }
}

/**
 * One app, as a Swift Backup card: package name, app name, a status line, tags, and a square checkbox.
 * Only plain values go in, so Compose skips cards that did not change when another app is ticked.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCard(
    label: String,
    pkg: String,
    status: String,
    warn: Boolean,
    tags: List<AppTag>,
    checked: Boolean,
    onLongClick: (() -> Unit)? = null,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val x = LocalExtra.current
    Surface(
        color = cs.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Row(
            Modifier
                .semantics(mergeDescendants = true) { stateDescription = if (checked) "Selected" else "Not selected" }
                .combinedClickable(onClick = onToggle, onLongClick = onLongClick, role = Role.Checkbox)
                .padding(start = 14.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppIcon(pkg, label, 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    pkg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warn) cs.error else cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tags.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { t ->
                            Tag(
                                t.text,
                                when (t.kind) {
                                    AppTag.BROWN -> x.tagBrown
                                    AppTag.RED -> x.tagRed
                                    AppTag.PURPLE -> cs.onSurfaceVariant
                                    else -> x.tagBlue
                                },
                            )
                        }
                    }
                }
            }
            CheckSquare(checked)
        }
    }
}

/**
 * One app inside a snapshot, Restoid style: icon, name, "Version: … - Size: …" and outlined component chips.
 * A ticked app gets a check badge on its icon.
 */
@Composable
fun SnapshotAppRow(
    label: String,
    pkg: String,
    line: String,
    chips: List<String>,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onToggle, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, end = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(48.dp)) {
                AppIcon(pkg, label, 48.dp)
                if (checked) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        null,
                        tint = cs.primary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .background(cs.surfaceContainerLow, CircleShape),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                if (chips.isNotEmpty()) ChipFlow(chips)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(chips: List<String>) {
    FlowRow(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { OutlineChip(it) }
    }
}
