package app.sunrestic

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AppInfo(
    val pkg: String,
    val label: String,
    val version: String,
    val uid: Int,
    val apks: List<String>,
    val snapshot: String = "",
    val perms: List<String> = emptyList(),
    /** Restore list only: when the newest snapshot containing this app was made. */
    val backedUpAt: String = "",
    /** Restore list only: version installed right now (null = not installed). */
    val installedVersion: String? = null,
    /** Snapshot view only: Component names saved for this app (from manifest.json). */
    val components: List<String> = emptyList(),
)

enum class Component(val label: String) {
    APK("APK"), DATA("Data"), DEVICE_DATA("Device data"), EXT_DATA("Ext-data"), MEDIA("Media"), OBB("Expansion");

    fun paths(a: AppInfo): List<String> = when (this) {
        APK -> a.apks
        DATA -> listOf("/data/data/${a.pkg}")
        DEVICE_DATA -> listOf("/data/user_de/0/${a.pkg}")
        EXT_DATA -> listOf("/sdcard/Android/data/${a.pkg}")
        MEDIA -> listOf("/sdcard/Android/media/${a.pkg}")
        OBB -> listOf("/sdcard/Android/obb/${a.pkg}")
    }
}

object AppBackup {
    val EXCLUDES = listOf(
        "/data/data/*/cache", "/data/data/*/code_cache",
        "/data/user_de/0/*/cache", "/data/user_de/0/*/code_cache",
        "/sdcard/Android/data/*/cache",
    )

    /** Runtime (dangerous) permissions that are currently granted to the app. */
    private fun grantedRuntimePerms(pm: PackageManager, pkg: String): List<String> = runCatching {
        val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val names = pi.requestedPermissions ?: return@runCatching emptyList<String>()
        val flags = pi.requestedPermissionsFlags ?: return@runCatching emptyList<String>()
        names.indices.filter { i ->
            (flags[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 &&
                runCatching {
                    pm.getPermissionInfo(names[i], 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE ==
                        PermissionInfo.PROTECTION_DANGEROUS
                }.getOrDefault(false)
        }.map { names[it] }
    }.getOrDefault(emptyList())

    /**
     * User-installed apps (and updated system apps like Chrome).
     * Permissions are NOT read here (slow); plan() reads them for the selected apps only.
     */
    fun listApps(ctx: Context, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<AppInfo> {
        val pm = ctx.packageManager
        val installed = pm.getInstalledApplications(0).filter {
            it.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        }
        val out = ArrayList<AppInfo>(installed.size)
        installed.forEachIndexed { i, ai ->
            out += AppInfo(
                pkg = ai.packageName,
                label = pm.getApplicationLabel(ai).toString(),
                version = runCatching { pm.getPackageInfo(ai.packageName, 0).versionName }.getOrNull() ?: "",
                uid = ai.uid,
                apks = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList()),
            )
            onProgress(i + 1, installed.size)
        }
        return out.sortedBy { it.label.lowercase() }
    }

    /** Builds the wanted path list and writes manifest.json (included in the snapshot). */
    fun plan(ctx: Context, apps: List<AppInfo>, comps: Set<Component>): List<String> {
        val pm = ctx.packageManager
        val paths = mutableListOf<String>()
        val manifest = JSONArray()
        for (a in apps) {
            paths += comps.flatMap { it.paths(a) }
            val perms = grantedRuntimePerms(pm, a.pkg)
            manifest.put(
                JSONObject().put("package", a.pkg).put("label", a.label).put("version", a.version)
                    .put("uid", a.uid).put("apks", JSONArray(a.apks))
                    .put("perms", JSONArray(perms))
                    .put("components", JSONArray(comps.map { it.name }))
            )
        }
        val f = File(ctx.filesDir, "app-manifest/manifest.json")
        f.parentFile?.mkdirs()
        f.writeText(JSONObject().put("version", 1).put("apps", manifest).toString(2))
        return paths + f.absolutePath
    }

    /**
     * Which of [pkgs] may be stopped. Never this app, and never a VPN app: the backup travels through it, so stopping it
     * would cut the connection in the middle of the job.
     */
    fun stoppable(pkgs: Collection<String>, self: String, vpn: Set<String>): List<String> =
        pkgs.filter { it != self && it !in vpn }

    /** Starts [script] as root, or returns null when root is refused or missing (so a refusal never crashes the app). */
    private fun su(script: String): Process? = try {
        ProcessBuilder("su", "--mount-master", "-c", script).redirectErrorStream(true).start()
    } catch (e: java.io.IOException) {
        null
    }

    /** Force-stops apps (root) so their files are not written during backup. */
    fun forceStop(pkgs: List<String>) {
        if (pkgs.isEmpty()) return
        val script = pkgs.joinToString("; ") { "am force-stop '$it'" }
        val p = su(script) ?: return
        p.inputStream.readBytes()
        p.waitFor()
    }

    /** restic fails on missing sources, so keep only paths that exist (checked as root). */
    fun existing(paths: List<String>, root: Boolean = true): List<String> {
        if (!root) return paths.filter { File(it).exists() }
        val list = paths.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val script = "for p in $list; do [ -e \"\$p\" ] && echo \"\$p\"; done"
        // Without root the check cannot run; the backup itself then fails with a clear message.
        val p = su(script) ?: return paths
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        return out.filter { it.startsWith("/") }
    }

    /** Total size in bytes of [paths] on this phone, without the cache folders the backup skips (read as root with `du`). */
    fun sizeOf(paths: List<String>): Long {
        if (paths.isEmpty()) return 0L
        fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"
        val all = paths.joinToString(" ") { q(it) }
        val caches = paths.filter { it.startsWith("/data/") || it.contains("/Android/data/") }
            .flatMap { listOf("$it/cache", "$it/code_cache") }.joinToString(" ") { q(it) }
        val script = "du -sk $all 2>/dev/null; echo ---; " + if (caches.isEmpty()) "true" else "du -sk $caches 2>/dev/null"
        val p = su(script) ?: return 0L
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        fun sum(lines: List<String>) = lines.sumOf { it.substringBefore('\t').trim().toLongOrNull() ?: 0L }
        val cut = out.indexOf("---").let { if (it < 0) out.size else it }
        return (sum(out.take(cut)) - sum(out.drop(cut + 1))).coerceAtLeast(0L) * 1024L
    }
}
