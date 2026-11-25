package app.sunrestic

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Swift Backup's "Cloud sync" page: the connection card, then the settings cards. */
@Composable
fun RepositoryScreen(m: AppModel) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val cs = MaterialTheme.colorScheme
    val busy = JobHub.busy
    var menu by remember { mutableStateOf(false) }

    var sheet by remember { mutableStateOf<String?>(null) }
    var dnsDialog by remember { mutableStateOf(false) }
    var rootDialog by remember { mutableStateOf(false) }

    ScreenScroll {
        SectionLabel("Overview")
        OverviewCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CardLabel(if (m.backend == "local") "Local repository" else m.backendLabel)
                    Text(
                        when {
                            !m.repoConfigured -> "Not set up"
                            m.backend == "sftp" -> m.host.trim()
                            m.backend == "local" -> m.localRepo.trim().substringAfterLast('/').ifEmpty { m.localRepo.trim() }
                            else -> m.repoSummary.substringAfter(':').removePrefix("//").substringBefore('/').ifEmpty { m.repoSummary }
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            !m.repoConfigured -> "Fill in the connection below"
                            m.password.isEmpty() -> "Locked"
                            else -> "Unlocked"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (m.ready) LocalExtra.current.good else cs.onSurfaceVariant,
                    )
                }
                IconBadge(
                    when (m.backend) { "local" -> Icons.Filled.PhoneAndroid; "sftp" -> Icons.Filled.Dns; else -> Icons.Filled.Cloud },
                    size = 48.dp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                when {
                    m.ready -> DangerPill("Lock", Icons.Filled.Lock, enabled = !busy) { m.lock() }
                    m.repoConfigured && m.vaultSaved -> PillButton("Unlock", strong = true, icon = Icons.Filled.Fingerprint, enabled = activity != null) {
                        activity?.let { m.unlockPassword(it) }
                    }
                    else -> Unit
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Initialize repository") },
                            enabled = !busy && m.ready,
                            onClick = {
                                menu = false
                                m.confirm = "Create a new restic repository at this location? Only do this once, on an empty location." to {
                                    m.go("restic init") { m.runner.init(it) }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Forget saved password") },
                            enabled = m.vaultSaved,
                            onClick = {
                                menu = false
                                m.confirm = "Forget the saved password? You will need to type it again." to { m.forgetPassword() }
                            },
                        )
                    }
                }
            }
        }

        InfoCard(
            "Location",
            Icons.Filled.Cloud,
            subtitle = "Where your backups are stored",
            onEdit = { sheet = "location" },
        ) {
            InfoRow(Icons.Filled.Cloud, "Type", when (m.backend) {
                "local" -> "This phone"; "rest" -> "REST server"; "b2" -> "Backblaze B2"; "s3" -> "S3-compatible"; else -> "SFTP"
            })
            when (m.backend) {
                "sftp" -> {
                    InfoRow(Icons.Filled.Dns, "Host", m.host)
                    InfoRow(Icons.Filled.Person, "User and port", if (m.user.isBlank()) "" else "${m.user}  ·  port ${m.port.ifBlank { "22" }}")
                    InfoRow(Icons.Filled.Folder, "Repo path on the server", m.repoPath)
                    InfoRow(Icons.Filled.Key, "SSH key", if (m.keyPath.isBlank()) "" else "Imported key file")
                }
                "local" -> InfoRow(Icons.Filled.Folder, "Folder on this phone", m.localRepo)
                "s3" -> {
                    InfoRow(Icons.Filled.Dns, "Endpoint", m.endpoint)
                    InfoRow(Icons.Filled.Storage, "Bucket", m.bucket)
                    InfoRow(Icons.Filled.Key, "Access key", masked(m.accessKey))
                    InfoRow(Icons.Filled.Lock, "Secret key", if (m.secretKey.isBlank()) "" else "••••••••")
                }
                "rest" -> {
                    InfoRow(Icons.Filled.Dns, "Server address", m.endpoint)
                    InfoRow(Icons.Filled.Person, "User", m.accessKey.ifBlank { "None" })
                }
                else -> {
                    InfoRow(Icons.Filled.Storage, "Bucket", m.bucket)
                    InfoRow(Icons.Filled.Key, "Account ID", masked(m.accessKey))
                    InfoRow(Icons.Filled.Lock, "Application key", if (m.secretKey.isBlank()) "" else "••••••••")
                }
            }
        }

        InfoCard(
            "Password",
            Icons.Filled.Key,
            subtitle = "Kept in the Android Keystore, unlocked with your fingerprint or screen lock",
            onEdit = { sheet = "password" },
        ) {
            InfoRow(Icons.Filled.Fingerprint, "Saved with fingerprint", if (m.vaultSaved) "Yes" else "No")
            InfoRow(Icons.Filled.Lock, "This session", if (m.password.isNotEmpty()) "Unlocked" else "Locked")
        }

        if (m.repoConfigured) {
            InfoCard(
                "New repository",
                Icons.Filled.AddCircle,
                subtitle = "Only needed the first time, on an empty location",
            ) {
                Text(
                    when {
                        m.ready -> "Nothing is stored here yet? Create the repository now. If this location already holds backups " +
                            "(for example from another phone), skip this and use Unlock instead."
                        else -> "Enter the password above first (Password card, pencil), then this button turns on."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                PillButton(
                    "Initialize repository",
                    Modifier.fillMaxWidth(),
                    strong = true,
                    icon = Icons.Filled.AddCircle,
                    enabled = !busy && m.ready,
                ) {
                    m.confirm = "Create a new restic repository at this location? Only do this once, on an empty location." to {
                        m.go("restic init") { m.runner.init(it) }
                    }
                }
            }
        }

        InfoCard("Options", Icons.Filled.Tune) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.weight(1f)) { InfoRow(Icons.Filled.Dns, "DNS for restic", m.dns) }
                IconButton(onClick = { dnsDialog = true }) { Icon(Icons.Filled.Edit, "Edit DNS", tint = cs.primary) }
            }
            SwitchRow(
                title = "Run as root (su)",
                subtitle = "Needed for app backups",
                checked = m.useRoot,
                icon = Icons.Filled.AdminPanelSettings,
                // Google Play wants the user told in advance, so the Play build asks before it turns root on.
                onChange = { if (it && BuildConfig.PLAY) rootDialog = true else m.useRoot = it },
            )
            SwitchRow(
                title = "Wi-Fi only",
                subtitle = "Don't start remote jobs on mobile data",
                checked = m.wifiOnly,
                icon = Icons.Filled.Wifi,
                onChange = { m.wifiOnly = it },
            )
            SwitchRow(
                title = "Verify after each backup",
                subtitle = "Runs a repository check; slower, but catches damage early",
                checked = m.verifyAfter,
                icon = Icons.Filled.VerifiedUser,
                onChange = { m.verifyAfter = it },
            )
        }
    }

    when (sheet) {
        "location" -> LocationSheet(m) { sheet = null }
        "password" -> PasswordSheet(m) { sheet = null }
    }
    if (dnsDialog) DnsDialog(m) { dnsDialog = false }
    if (rootDialog) {
        AlertDialog(
            onDismissRequest = { rootDialog = false },
            icon = { Icon(Icons.Filled.AdminPanelSettings, null) },
            title = { Text("Use root access?") },
            text = {
                Text(
                    "This uses root access that you already have on this phone (a superuser app such as Magisk will ask you to allow it).\n\n" +
                        "Sunrestic does not root your phone and does not change your system. With root it can read and restore the private " +
                        "data of the apps you choose, which Android otherwise keeps out of reach. It does this only while a backup or restore " +
                        "you started is running.\n\nYou can turn this off again at any time, and everything that does not need root keeps working.",
                )
            },
            confirmButton = { TextButton(onClick = { m.useRoot = true; rootDialog = false }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { rootDialog = false }) { Text("Cancel") } },
            containerColor = cs.surfaceContainerLow,
        )
    }
}

/** First four characters then dots, so a key can be recognised without showing it. */
private fun masked(v: String) = if (v.isBlank()) "" else v.take(4) + "••••••••"

/** All connection settings in one sheet. Nothing changes until Save; Cancel (or swiping away) throws the edits away. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LocationSheet(m: AppModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val original = remember { m.repoDraft() }
    var d by remember { mutableStateOf(original) }

    val repoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = treeUriToPath(uri)
            if (path != null) d = d.copy(localRepo = path)
            else Toast.makeText(ctx, "Can't map that folder to a path. Type it instead.", Toast.LENGTH_LONG).show()
        }
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) m.importKeyFile(uri)?.let { d = d.copy(keyPath = it) }
    }

    SheetFrame(onDismiss, ime = true) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Edit location", style = MaterialTheme.typography.titleLarge)
            Text(
                "Changes are applied when you tap Save.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip("SFTP", d.backend == "sftp", icon = Icons.Filled.Dns) { d = d.copy(backend = "sftp") }
            ChoiceChip("This phone", d.backend == "local", icon = Icons.Filled.PhoneAndroid) { d = d.copy(backend = "local") }
            ChoiceChip("S3", d.backend == "s3", icon = Icons.Filled.Cloud) { d = d.copy(backend = "s3") }
            ChoiceChip("REST server", d.backend == "rest", icon = Icons.Filled.Language) { d = d.copy(backend = "rest") }
            ChoiceChip("Backblaze B2", d.backend == "b2", icon = Icons.Filled.Storage) { d = d.copy(backend = "b2") }
        }
        when (d.backend) {
            "sftp" -> {
                Field("Host", d.host, placeholder = "Hostname or IP address", icon = Icons.Filled.Dns) { d = d.copy(host = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("User", d.user, Modifier.weight(1f), icon = Icons.Filled.Person) { d = d.copy(user = it) }
                    Field("Port", d.port, Modifier.weight(0.7f), number = true) { d = d.copy(port = it) }
                }
                Field(
                    "Repo path on the server", d.repoPath, placeholder = "Absolute path, e.g. /srv/backups/phone",
                    icon = Icons.Filled.Folder,
                ) { d = d.copy(repoPath = it) }
                Field(
                    "SSH private key file",
                    if (d.keyPath.isBlank()) "" else d.keyPath.substringAfterLast('/'),
                    placeholder = "Tap the folder to import a key file",
                    icon = Icons.Filled.Key,
                    trailing = {
                        IconButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FolderOpen, "Import key file") }
                    },
                ) { d = d.copy(keyPath = it) }
            }
            "local" -> Field(
                "Folder on this phone", d.localRepo, placeholder = "Absolute path to a folder", icon = Icons.Filled.Folder,
                trailing = { IconButton(onClick = { repoPicker.launch(null) }) { Icon(Icons.Filled.FolderOpen, "Choose folder") } },
            ) { d = d.copy(localRepo = it) }
            "s3" -> {
                Field(
                    "Endpoint", d.endpoint, placeholder = "https://s3.amazonaws.com or your MinIO / Wasabi / R2 address",
                    icon = Icons.Filled.Dns,
                ) { d = d.copy(endpoint = it) }
                Field("Bucket (and optional folder)", d.bucket, placeholder = "my-bucket/phone", icon = Icons.Filled.Storage) { d = d.copy(bucket = it) }
                Field("Access key", d.accessKey, icon = Icons.Filled.Key) { d = d.copy(accessKey = it) }
                Field("Secret key", d.secretKey, secret = true, icon = Icons.Filled.Lock) { d = d.copy(secretKey = it) }
                Text(
                    "The keys are stored in this app's private storage. Use a key that can only reach this bucket.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            "rest" -> {
                Field(
                    "Server address", d.endpoint, placeholder = "https://backup.example.com:8000/phone", icon = Icons.Filled.Dns,
                ) { d = d.copy(endpoint = it) }
                Field("User (optional)", d.accessKey, icon = Icons.Filled.Person) { d = d.copy(accessKey = it) }
                Field("Password (optional)", d.secretKey, secret = true, icon = Icons.Filled.Lock) { d = d.copy(secretKey = it) }
            }
            else -> {
                Field("Bucket (and optional folder)", d.bucket, placeholder = "my-bucket:phone", icon = Icons.Filled.Storage) { d = d.copy(bucket = it) }
                Field("Account ID or key ID", d.accessKey, icon = Icons.Filled.Key) { d = d.copy(accessKey = it) }
                Field("Application key", d.secretKey, secret = true, icon = Icons.Filled.Lock) { d = d.copy(secretKey = it) }
            }
        }
        if (d.missing.isNotEmpty()) {
            Text("Still needed: ${d.missing.joinToString()}", style = MaterialTheme.typography.bodyMedium, color = cs.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            PillButton("Cancel", Modifier.weight(1f)) { onDismiss() }
            PillButton("Save", Modifier.weight(1f), strong = true, icon = Icons.Filled.Check, enabled = d.complete && d != original) {
                m.applyRepository(d)
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }
    }
}

/** Type a new repository password, and choose whether to keep it behind the fingerprint. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordSheet(m: AppModel, onDismiss: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val cs = MaterialTheme.colorScheme
    var pw by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    SheetFrame(onDismiss, scrollable = false, ime = true) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Repository password", style = MaterialTheme.typography.titleLarge)
            Text(
                "The password you chose when the repository was created. It is never sent anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }
        Field("Password", pw, secret = true, icon = Icons.Filled.Key) { pw = it }
        SwitchRow(
            title = "Remember with fingerprint",
            subtitle = "Unlock next time with your fingerprint or screen lock",
            checked = remember,
            icon = Icons.Filled.Fingerprint,
            onChange = { remember = it },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            PillButton("Cancel", Modifier.weight(1f)) { onDismiss() }
            PillButton("Save", Modifier.weight(1f), strong = true, icon = Icons.Filled.Check, enabled = pw.isNotEmpty()) {
                m.applyPassword(pw, remember, activity)
                onDismiss()
            }
        }
    }
}

@Composable
private fun DnsDialog(m: AppModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(m.dns) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Dns, null) },
        title = { Text("DNS for restic") },
        text = { Field("Server (host:port)", text, icon = Icons.Filled.Dns) { text = it } },
        confirmButton = { TextButton(onClick = { m.dns = text.trim(); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
}
