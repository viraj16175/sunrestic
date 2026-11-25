package app.sunrestic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The icon that says what a folder is for, from its name: DCIM is a camera, Pictures an image, Downloads an arrow. */
fun folderIcon(path: String): ImageVector {
    val name = path.trimEnd('/').substringAfterLast('/').lowercase()
    return when {
        name in setOf("dcim", "camera") -> Icons.Filled.CameraAlt
        name in setOf("screenshots", "screenshot") -> Icons.Filled.Screenshot
        name in setOf("pictures", "images", "photos", "photo", "wallpapers") -> Icons.Filled.Image
        name in setOf("documents", "docs", "document", "files") -> Icons.Filled.Description
        name in setOf("download", "downloads") -> Icons.Filled.Download
        name in setOf("music", "audio", "audiobooks", "podcasts", "ringtones") -> Icons.Filled.MusicNote
        name in setOf("movies", "videos", "video") -> Icons.Filled.Movie
        name in setOf("recordings", "voice recorder", "voicerecorder", "sound recorder") -> Icons.Filled.Mic
        name in setOf("alarms") -> Icons.Filled.Alarm
        name in setOf("books", "ebooks") -> Icons.Filled.MenuBook
        name in setOf("backups", "backup") -> Icons.Filled.Backup
        name == "android" -> Icons.Filled.Android
        name in setOf("whatsapp", "telegram", "signal", "messenger", "threema") -> Icons.Filled.Chat
        else -> Icons.Filled.Folder
    }
}

/** A row of round folder icons, like the app icons on an apps snapshot. The rest become a "+N" circle. */
@Composable
fun FolderStrip(paths: List<String>) {
    val size = 36.dp
    val gap = 8.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fit = ((maxWidth + gap) / (size + gap)).toInt().coerceAtLeast(1)
        val more = paths.size > fit
        val shown = if (more) paths.take(fit - 1) else paths
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            shown.forEach { IconBadge(folderIcon(it), size = size) }
            if (more) {
                Box(
                    Modifier.size(size).clip(CircleShape).let { it },
                    contentAlignment = Alignment.Center,
                ) {
                    IconBadgeText("+${paths.size - shown.size}", size)
                }
            }
        }
    }
}

@Composable
private fun IconBadgeText(text: String, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.material3.Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
