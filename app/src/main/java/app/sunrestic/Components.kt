package app.sunrestic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---- page scaffolding -------------------------------------------------------------

/** Standard scrolling page used by the tabs. */
@Composable
fun ScreenScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, top = 2.dp, end = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Big round button, like Swift Backup's search button and avatar. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    description: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    container: Color = Color.Transparent,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    badge: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = tint,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(size * 0.5f))
            if (badge) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = size * 0.2f, end = size * 0.2f)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
            }
        }
    }
}

/** Main header: app name with a quiet status line on the left, plain icon actions on the right. */
@Composable
fun MainHeader(
    title: String,
    badge: String?,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (badge != null) {
                Text(badge, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
            }
        }
        left()
        right()
    }
}

/** Sub page bar: back arrow, title with a quiet second line, icon actions. */
@Composable
fun SwiftTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            @Suppress("DEPRECATION")
            Icon(Icons.Filled.ArrowBack, "Back", Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

// ---- labels ------------------------------------------------------------------------

/** Swift Backup's grey uppercase heading above a group ("OVERVIEW", "APPS QUICK ACTIONS"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.padding(start = 6.dp, top = 8.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Small uppercase label inside a card ("INTERNAL STORAGE", "ROOT STATUS"). */
@Composable
fun CardLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- cards and groups ----------------------------------------------------------------

/** Corner shape for item [i] of [n] stacked cards: big outer corners, small inner ones (Swift quick actions). */
fun groupShape(i: Int, n: Int, big: Dp = 20.dp, small: Dp = 4.dp): Shape {
    val top = if (i == 0) big else small
    val bottom = if (i == n - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Stacked cards with small gaps between them. Null rows are skipped. */
@Composable
fun Group(vararg rows: (@Composable () -> Unit)?) {
    val list = rows.filterNotNull()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        list.forEachIndexed { i, row ->
            Surface(
                shape = groupShape(i, list.size),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) { row() }
        }
    }
}

/** Cards are flat in both modes: they stand out by tone, not by outlines. */
@Composable
fun cardBorder(): BorderStroke? = null

/** Big card (Swift "Overview") with a faint accent glow at the top. */
@Composable
fun OverviewCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
        border = cardBorder(),
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A card with a bold title and a grey description, like Swift's "Cloud backup tag" card. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = cardBorder(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) IconBadge(icon, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            content()
        }
    }
}

/**
 * A card that only shows values. Changing them is a deliberate step: tap the pencil, edit in a sheet, then Save.
 * That way nothing can be changed by accident while looking around.
 */
@Composable
fun InfoCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onEdit: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = cardBorder(),
    ) {
        Column(Modifier.padding(start = 18.dp, top = 12.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(icon, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (onEdit != null) {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit $title", tint = MaterialTheme.colorScheme.primary) }
                }
            }
            Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** One read-only fact: small icon, grey label, value. [value] empty shows "Not set". */
@Composable
fun InfoRow(icon: ImageVector, label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Text(
                value.ifBlank { "Not set" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (value.isBlank()) cs.error else cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Grey rounded button ("From device", "From cloud"). [strong] uses the accent colour. */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    strong: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (strong) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = if (strong) cs.onPrimaryContainer else cs.onSurface,
        modifier = modifier.heightIn(min = 42.dp).alpha(if (enabled) 1f else 0.45f),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

/** Round chip for picking one or several of a few choices (reminder hour, tags). */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp))
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/** Red rounded button (Swift "Disconnect"). */
@Composable
fun DangerPill(text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = LocalExtra.current.dark
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = cs.errorContainer,
        contentColor = if (dark) cs.onErrorContainer else cs.error,
        modifier = modifier.heightIn(min = 42.dp).alpha(if (enabled) 1f else 0.45f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Rounded progress bar: accent fill on a soft track. */
@Composable
fun SwiftBar(progress: Float, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(450), label = "bar")
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val h = size.height
        drawRoundRect(track, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        if (p > 0f) {
            drawRoundRect(fill, size = androidx.compose.ui.geometry.Size(maxOf(h, size.width * p), h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        }
    }
}

// ---- pills, tags, icons ----------------------------------------------------------------

/** Quiet tag on an app card: tinted background, coloured text, sentence case. */
@Composable
fun Tag(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f), contentColor = color) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** Restoid's outlined component chip ("APK", "Data", "Permissions"). */
@Composable
fun OutlineChip(text: String, dim: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.alpha(if (dim) 0.4f else 1f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Small tonal pill for facts. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(modifier = modifier, shape = CircleShape, color = container, contentColor = content) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Round icon on a tinted background. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 36.dp,
) {
    Box(modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/** Swift Backup's square checkbox. */
@Composable
fun CheckSquare(checked: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val fill = cs.primary
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier
            .size(24.dp)
            .clip(shape)
            .then(if (checked) Modifier.background(fill) else Modifier.border(1.5.dp, cs.outline, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, null, Modifier.size(17.dp), tint = cs.onPrimary)
    }
}

/** Rounded extended button floating over the content (Restoid "Run Tasks", Swift "Backup options"). */
@Composable
fun BigFab(text: String, icon: ImageVector, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ExtendedFloatingActionButton(
        onClick = { if (enabled) onClick() },
        modifier = modifier.alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(16.dp),
        containerColor = cs.primaryContainer,
        contentColor = cs.onPrimaryContainer,
        icon = { Icon(icon, null, Modifier.size(20.dp)) },
        text = { Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp, end = 4.dp)) },
    )
}

// ---- inputs and rows ---------------------------------------------------------------------

@Composable
fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    number: Boolean = false,
    placeholder: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder != null) ({ Text(placeholder) }) else null,
        leadingIcon = if (icon != null) ({ Icon(icon, null) }) else null,
        trailingIcon = when {
            secret -> ({
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        if (reveal) "Hide" else "Show",
                    )
                }
            })
            trailing != null -> trailing
            else -> null
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = cs.surfaceContainerHigh,
            unfocusedContainerColor = cs.surfaceContainerHigh,
            disabledContainerColor = cs.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
    )
}

/** The whole row is tappable, not just the switch. */
@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) IconBadge(icon, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked, onChange)
    }
}

/** A row with an optional leading and trailing element. No built-in horizontal padding, so it fits inside cards. */
@Composable
fun ItemRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleLines: Int = 1,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** A tappable settings row with a round icon and a chevron. Meant to sit in a [Group]. */
@Composable
fun ActionRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ItemRow(
        title = title,
        subtitle = subtitle,
        subtitleLines = 2,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        leading = {
            IconBadge(
                icon,
                container = if (destructive) cs.errorContainer else cs.primaryContainer,
                tint = if (destructive) cs.onErrorContainer else cs.onPrimaryContainer,
            )
        },
        trailing = { Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant) },
    )
}

/** A coloured notice with an icon and short text. */
@Composable
fun NoticeCard(
    icon: ImageVector,
    title: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.errorContainer,
    content: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = content)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconBadge(icon, size = 60.dp)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

/** Placeholder cards shown while a list loads. One shared pulse, drawn without recomposing. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 8) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        modifier.fillMaxWidth().graphicsLayer { alpha = pulse }.padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(card).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(block))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.fillMaxWidth(0.4f).height(8.dp).clip(CircleShape).background(block))
                    Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(CircleShape).background(block))
                    Box(Modifier.fillMaxWidth(0.45f).height(8.dp).clip(CircleShape).background(block))
                }
            }
        }
    }
}

// ---- running job and log -------------------------------------------------------------------


/** Progress of the running job, shown at the top of every page. */
@Composable
fun JobBanner(onDetails: () -> Unit) {
    if (!JobHub.busy) return
    val p = JobHub.progress
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = { if (JobHub.screenJob) JobHub.screenOpen = true else onDetails() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).animateContentSize(),
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
    ) {
        Column(Modifier.padding(start = 18.dp, top = 12.dp, end = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    CardLabel("Running")
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (p != null) "${(p.percent * 100).toInt()}%" else "Working…",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (p != null) {
                            Text(
                                "  ${fmtBytes(p.bytesDone)} of ${fmtBytes(p.totalBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
                }
                TextButton(onClick = onDetails) { Text("Log") }
                TextButton(onClick = { JobHub.cancel() }) { Text("Cancel", color = cs.error) }
            }
            Box(Modifier.padding(end = 12.dp)) {
                if (p != null) {
                    SwiftBar(p.percent.toFloat())
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = cs.primary,
                        trackColor = cs.surfaceContainerHighest,
                    )
                }
            }
            if (p != null) {
                val eta = fmtEta(p.secondsRemaining)
                Text(
                    "${p.filesDone}/${p.totalFiles} files" + if (eta.isNotEmpty()) " · $eta" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    // One list item per line: only the visible lines are laid out, however long the log gets.
    val lines = remember(JobHub.log) { JobHub.log.lines() }

    // Follow the end of the log, but stop following as soon as the user scrolls up.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount == 0 || (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 1
        }
    }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) follow = atBottom
    }
    LaunchedEffect(lines.size) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Log",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("log", JobHub.log))
                }) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy")
                }
                TextButton(onClick = { JobHub.log = "" }) {
                    Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Clear")
                }
            }
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                if (JobHub.log.isEmpty()) {
                    Text("Output appears here.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        itemsIndexed(lines) { _, line ->
                            Text(if (line.isEmpty()) " " else line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---- formatting ----------------------------------------------------------------------------

/** "6 mins ago", "3 hours ago", "2 days ago". [t] is a restic time string. */
fun relativeTime(t: String): String {
    val ms = parseResticTime(t) ?: return ""
    val mins = (System.currentTimeMillis() - ms) / 60_000
    return when {
        mins < 1 -> "just now"
        mins == 1L -> "1 min ago"
        mins < 60 -> "$mins mins ago"
        mins < 120 -> "1 hour ago"
        mins < 48 * 60 -> "${mins / 60} hours ago"
        else -> "${mins / (24 * 60)} days ago"
    }
}

/** Short "how long ago" for a time in epoch ms: "just now", "5 min ago", "3 h ago", "4 days ago". */
fun agoText(t: Long): String {
    val mins = (System.currentTimeMillis() - t) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ago"
        else -> "${mins / (24 * 60)} days ago"
    }
}

/** Parses "2026-11-03T08:12:45.123456789+05:30" (or …Z) to epoch ms. */
fun parseResticTime(t: String): Long? = runCatching {
    val main = t.take(19) // yyyy-MM-ddTHH:mm:ss
    val zone = Regex("(Z|[+-]\\d{2}:\\d{2})$").find(t)?.value ?: "Z"
    java.time.OffsetDateTime.parse(main + zone).toInstant().toEpochMilli()
}.getOrNull()

// ---- bottom bar --------------------------------------------------------------------------

/** Slim bottom bar: small icon in a pill when selected, 11sp label. Replaces the tall Material bar. */
@Composable
fun CompactNavBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
fun RowScope.CompactNavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.width(48.dp).height(26.dp).clip(CircleShape)
                .background(if (selected) cs.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, label, Modifier.size(20.dp),
                tint = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Small coloured dot before a status word. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}

/** The frame every bottom sheet shares: the lifted colour, room above the navigation bar and, by default, scrolling. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SheetFrame(
    onDismiss: () -> Unit,
    gap: androidx.compose.ui.unit.Dp = 12.dp,
    scrollable: Boolean = true,
    ime: Boolean = false, // keep clear of the keyboard (sheets with text fields)
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .then(if (ime) Modifier.imePadding() else Modifier)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 18.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
            content = content,
        )
    }
}

/** A selectable card with a radio button, an icon, a title and a grey line (the selected one takes the accent colour). */
@Composable
fun ChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Icon(icon, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant)
            }
        }
    }
}

/** One row of a "pick one" list: a radio button, a title and a grey line. */
@Composable
fun PickRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}
