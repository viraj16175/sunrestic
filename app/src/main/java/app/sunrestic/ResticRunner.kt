package app.sunrestic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class Config(
    val host: String,
    val port: String,
    val user: String,
    val repoPath: String,
    val keyPath: String,
    val password: String,
    val dns: String,
    val useRoot: Boolean,
    val remote: Boolean,
    val localRepo: String,
    /** "sftp", "local", "s3" (any S3-compatible), "rest" (restic REST server) or "b2" (Backblaze). */
    val backend: String = if (remote) "sftp" else "local",
    /** s3: endpoint like https://s3.example.com. rest: server URL. */
    val endpoint: String = "",
    /** s3: bucket or bucket/prefix. b2: bucket or bucket:path. */
    val bucket: String = "",
    /** s3: access key. b2: account id. rest: user name. */
    val accessKey: String = "",
    /** s3: secret key. b2: application key. rest: password. */
    val secretKey: String = "",
)

/**
 * Where the app list (manifest.json) is inside a snapshot. The path contains the app ID of the app that made the backup
 * (for example /data/user/0/app.resticbackup/files/…), so it is taken from the snapshot itself and not from this app's folder.
 */
internal fun manifestPathIn(paths: List<String>, fallback: String): String =
    paths.firstOrNull { it.endsWith("/app-manifest/manifest.json") } ?: fallback

data class SnapInfo(
    val id: String,
    val time: String,
    val tags: List<String>,
    val paths: Int = 0,
    val totalBytes: Long = 0,
    val dataAdded: Long = 0,
    val files: Long = 0,
    /** The backed-up paths themselves (folders snapshots show these). */
    val pathList: List<String> = emptyList(),
)

/** What a "keep newest N" prune would do. Snapshots that are the newest copy of an app are never in [delete]. */
data class PrunePlan(val delete: List<SnapInfo>, val protectedSnapshots: Int, val protectedApps: List<String>)

/** Messages restic prints when restoring onto /sdcard that do not mean any file is missing. */
internal fun isHarmlessRestoreError(message: String): Boolean =
    "failed to restore timestamp" in message || "lchown /sdcard" in message || "lchown /storage" in message ||
        (message.contains("chown") && message.contains("/sdcard"))

/** The restic repository string (without secrets) for the chosen backend. */
internal fun repoUrlFor(c: Config): String = when (c.backend) {
    "local" -> c.localRepo
    "s3" -> "s3:" + c.endpoint.trim().trimEnd('/') + "/" + c.bucket.trim().trim('/')
    "rest" -> "rest:" + c.endpoint.trim()
    "b2" -> "b2:" + c.bucket.trim()
    else -> "sftp:${c.user}@${c.host}:${c.repoPath}"
}

/** Runs the bundled static binaries (librestic.so, libssh.so), optionally through `su`. */
class ResticRunner(private val ctx: Context) {
    private val libDir = ctx.applicationInfo.nativeLibraryDir
    private val restic = "$libDir/librestic.so"
    private val ssh = "$libDir/libssh.so"
    private val knownHosts = File(ctx.filesDir, "known_hosts").absolutePath

    @Volatile private var current: Process? = null

    /**
     * true  = the script (including RESTIC_PASSWORD) is sent to `su` on stdin, so it never appears in the process list.
     * false = old behaviour (script passed as the `-c` argument). Flip this if your su does not accept `sh -s` on stdin.
     */
    private val scriptViaStdin = true

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun env(c: Config) = mapOf(
        "HOME" to ctx.filesDir.absolutePath,
        "TMPDIR" to ctx.cacheDir.absolutePath,
        "RESTIC_CACHE_DIR" to File(ctx.cacheDir, "restic").absolutePath,
        "RESTIC_PASSWORD" to c.password,
        "RESTIC_DNS" to c.dns,
        "RESTIC_PROGRESS_FPS" to "2",
        "SSH_USER" to c.user,
        "SSL_CERT_DIR" to "/system/etc/security/cacerts",
    ) + when (c.backend) {
        "s3" -> mapOf("AWS_ACCESS_KEY_ID" to c.accessKey, "AWS_SECRET_ACCESS_KEY" to c.secretKey)
        "b2" -> mapOf("B2_ACCOUNT_ID" to c.accessKey, "B2_ACCOUNT_KEY" to c.secretKey)
        "rest" -> mapOf("RESTIC_REST_USERNAME" to c.accessKey, "RESTIC_REST_PASSWORD" to c.secretKey)
        else -> emptyMap()
    }

    /** Runs a binary and writes its raw standard output to [sink] (error output is read and dropped). Returns the exit code. */
    private fun streamBytes(c: Config, binary: String, args: List<String>, sink: java.io.OutputStream): Int {
        val vars = env(c)
        val exec = buildString {
            append("exec ").append(q(binary))
            args.forEach { append(' ').append(q(it)) }
        }
        val exports = buildString { vars.forEach { (k, v) -> append("export ").append(k).append('=').append(q(v)).append('\n') } }
        var stdinScript: String? = null
        val pb = when {
            !c.useRoot -> ProcessBuilder("sh", "-c", exec).also { it.environment().putAll(vars) }
            else -> {
                stdinScript = exports + exec + "\n"
                ProcessBuilder("su", "--mount-master", "-c", "sh -s")
            }
        }
        val p = try {
            pb.start()
        } catch (e: java.io.IOException) {
            return START_FAILED // the caller reports the exit code; the reason is in startFailure()
        }
        current = p
        try {
            p.outputStream.use { out -> stdinScript?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
            val errors = Thread { runCatching { p.errorStream.readBytes() } }.also { it.isDaemon = true; it.start() }
            p.inputStream.copyTo(sink)
            errors.join(2000)
            return p.waitFor()
        } finally {
            current = null
        }
    }

    /** Exit code used when the process could not even be started. */
    private val START_FAILED = 127

    /** Plain words for "the process could not be started", with the likely fix when root is on. */
    private fun startFailure(c: Config, e: java.io.IOException): String =
        "error: could not start the program (${e.message}). " +
            if (c.useRoot) "Root access was refused or is missing. Allow this app in your root manager (Magisk or KernelSU), or turn off Run as root in the Repo tab."
            else "Check that the app was installed completely."

    /** Runs a binary and calls [onLine] for every output line (stdout and stderr merged). */
    private fun stream(c: Config, binary: String, args: List<String>, onLine: (String) -> Unit): Int {
        val vars = env(c)
        val exec = buildString {
            append("exec ").append(q(binary))
            args.forEach { append(' ').append(q(it)) }
        }
        val exports = buildString {
            vars.forEach { (k, v) -> append("export ").append(k).append('=').append(q(v)).append('\n') }
        }
        var stdinScript: String? = null
        val pb = when {
            // Not root: no su involved, so the environment can be set directly.
            !c.useRoot -> ProcessBuilder("sh", "-c", exec).also { it.environment().putAll(vars) }
            scriptViaStdin -> {
                stdinScript = exports + exec + "\n"
                ProcessBuilder("su", "--mount-master", "-c", "sh -s")
            }
            else -> ProcessBuilder("su", "--mount-master", "-c", exports.replace("\n", "; ") + exec)
        }
        pb.redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (e: java.io.IOException) {
            onLine(startFailure(c, e)) // for example root was refused: report it, never crash
            return START_FAILED
        }
        current = p
        try {
            p.outputStream.use { out -> stdinScript?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
            p.inputStream.bufferedReader().forEachLine(onLine)
            return p.waitFor()
        } finally {
            current = null
        }
    }

    /** Stops the running job. Under root, also kills the real restic/ssh processes by name. */
    fun cancel(c: Config) {
        current?.destroy()
        if (c.useRoot) {
            try {
                ProcessBuilder("su", "--mount-master", "-c", "pkill -f 'lib[r]estic.so'; pkill -f 'lib[s]sh.so'").start().waitFor()
            } catch (_: Exception) {
            }
        }
    }

    private fun sh(c: Config, binary: String, args: List<String>): String {
        val sb = StringBuilder()
        val code = stream(c, binary, args) { sb.appendLine(it) }
        return sb.toString().trimEnd() + "\n[exit $code]"
    }

    /** ssh options. Android has no resolv.conf for the static ssh, so we resolve the host here. */
    private fun sshArgs(c: Config): List<String> {
        val ip = InetAddress.getByName(c.host).hostAddress ?: error("cannot resolve ${c.host}")
        return listOf(
            "-i", c.keyPath,
            "-p", c.port,
            "-o", "HostName=$ip",
            "-o", "HostKeyAlias=${c.host}",
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "UserKnownHostsFile=$knownHosts",
            "-o", "BatchMode=yes",
        )
    }

    private fun repoUrl(c: Config): String = repoUrlFor(c)

    /** sftp goes through our ssh; local is a folder on the phone; s3, rest and b2 are restic's own backends. */
    private fun resticArgs(c: Config, vararg sub: String): List<String> {
        if (c.backend != "sftp") return listOf("-r", repoUrl(c)) + sub
        val sftpCmd = (listOf(ssh) + sshArgs(c) + listOf("${c.user}@${c.host}", "-s", "sftp")).joinToString(" ")
        return listOf("-r", repoUrl(c), "-o", "sftp.command=$sftpCmd") + sub
    }

    /** Quick TCP check before a long job. Returns null when the server answers, otherwise a message. */
    fun reachable(c: Config): String? {
        val (host, port) = when (c.backend) {
            "sftp" -> c.host to (c.port.toIntOrNull() ?: 22)
            "s3", "rest" -> {
                val raw = c.endpoint.trim()
                val u = runCatching { java.net.URI(if ("://" in raw) raw else "https://$raw") }.getOrNull()
                val h = u?.host ?: return null
                h to (if (u.port > 0) u.port else if (u.scheme == "http") 80 else 443)
            }
            else -> return null // local folder, or b2 (its own API hosts)
        }
        return try {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(host), port), 5000) }
            null
        } catch (e: Exception) {
            "cannot reach $host:$port (${e.message})"
        }
    }

    // ---- simple commands ---------------------------------------------------

    fun versions(c: Config) =
        sh(c, restic, listOf("version")) + "\n" + sh(c, ssh, listOf("-V"))

    fun testSsh(c: Config) =
        sh(c, ssh, sshArgs(c) + listOf("${c.user}@${c.host}", "echo", "ssh-ok;", "uname", "-a"))

    fun init(c: Config) = sh(c, restic, resticArgs(c, "init"))
    fun snapshots(c: Config) = sh(c, restic, resticArgs(c, "snapshots"))

    /** forget + prune. Extra args are snapshot IDs or retention flags. */
    fun forget(c: Config, vararg args: String) =
        sh(c, restic, resticArgs(c, "forget", "--prune") + args)

    /** forget + prune for an explicit list of snapshot IDs. */
    fun forgetIds(c: Config, ids: List<String>) =
        sh(c, restic, resticArgs(c, "forget", "--prune") + ids)

    /** Removes ALL repo locks. Only safe when nothing else is using the repo. */
    fun unlock(c: Config) = sh(c, restic, resticArgs(c, "unlock", "--remove-all"))

    /** Real size of the repo's data. */
    fun stats(c: Config) = sh(c, restic, resticArgs(c, "stats", "--mode", "raw-data"))

    /** Bytes the repository really stores (deduplicated, compressed), or null when it cannot be read. */
    fun repoBytes(c: Config): Long? {
        val lines = ArrayList<String>()
        val code = stream(c, restic, resticArgs(c, "stats", "--mode", "raw-data", "--json")) { lines += it }
        if (code != 0) return null
        return lines.firstNotNullOfOrNull { l ->
            runCatching { JSONObject(l).getLong("total_size") }.getOrNull()
        }
    }

    /** Reclaim all unused space (may repack, can be slow over sftp). */
    fun prune(c: Config) = sh(c, restic, resticArgs(c, "prune", "--max-unused", "0"))

    /** `restic check`. [subset] like "5%" also reads that share of the actual data. Output goes live into the log. */
    fun checkCode(c: Config, subset: String?, onLine: (String) -> Unit): Int {
        val args = resticArgs(c, "check") + (if (subset != null) listOf("--read-data-subset=$subset") else emptyList())
        unlockStale(c) // a cancelled or killed job leaves a lock behind, and a check needs the repository to itself
        var locked = false
        var code = stream(c, restic, args) { if ("locked" in it) locked = true; onLine(it) }
        if (code != 0 && locked) {
            onLine("a leftover lock was in the way; clearing stale locks and trying again")
            unlockStale(c)
            code = stream(c, restic, args, onLine)
        }
        return code
    }

    /**
     * Gives every snapshot that carries the tag [old] the tag [new] instead. restic writes new snapshots with the
     * changed tags and removes the old ones, so the snapshot IDs change. Nothing else in the repository is touched.
     */
    fun renameSnapshotTag(c: Config, old: String, new: String): String =
        sh(c, restic, resticArgs(c, "tag", "--host", "android", "--tag", old, "--remove", old, "--add", new))

    /** Removes locks of processes that no longer exist (a cancelled job). Locks of running clients stay. */
    fun unlockStale(c: Config): Int = stream(c, restic, resticArgs(c, "unlock")) { }

    fun checkText(c: Config, subset: String?): String {
        val code = checkCode(c, subset) { JobHub.append(it + "\n") }
        return "[exit $code]"
    }

    /** Re-enables apps that an interrupted restore may have left disabled. Root only. */
    fun enablePackages(pkgs: List<String>) {
        if (pkgs.isEmpty()) return
        rootSh(pkgs.joinToString("; ") { "pm enable '$it'" })
    }

    // ---- backup / restore of folders ---------------------------------------

    fun backup(c: Config, path: String, onEvent: (ResticEvent) -> Unit): Int =
        stream(c, restic, resticArgs(c, "backup", "--json", "--host", "android", "--tag", "android-test", path)) {
            onEvent(ResticJson.parse(it))
        }

    /**
     * Restores a folders snapshot into [target]. [snap] = "latest" picks the newest *folders* snapshot
     * (so an app snapshot is never restored into a folder by accident); otherwise it is a snapshot ID.
     */
    /**
     * Restores a folders snapshot. [target] "/" puts every saved folder back into its own original place. Any other
     * [target] is an ordinary folder: each saved folder goes into its own subfolder there. [overwrite] is restic's rule
     * for files that already exist: "if-changed", "if-newer" or "never".
     *
     * Each folder is restored on its own (snapshot:folder into a target folder). Restoring the whole snapshot to "/"
     * would try to replace /sdcard, which on Android is a symlink on a read-only root, and fails.
     */
    fun restoreFolders(c: Config, target: String, snap: String, overwrite: String, onEvent: (ResticEvent) -> Unit): Int {
        val tagFilter = if (snap == "latest") arrayOf("--tag", "android-folders") else emptyArray()
        val rule = if (overwrite in listOf("if-changed", "if-newer", "never")) listOf("--overwrite", overwrite) else emptyList()
        val all = listSnapshots(c, *tagFilter)
        val chosen = if (snap == "latest") all.lastOrNull() else all.firstOrNull { it.id == snap }
        val inPlace = target == "/" || target.isBlank()
        if (chosen == null) {
            onEvent(ResticEvent.Error("that backup was not found in the repository"))
            return 1
        }
        if (chosen.pathList.isEmpty()) {
            if (inPlace) {
                onEvent(ResticEvent.Error("this backup does not say which folders it came from; choose another folder to restore into"))
                return 1
            }
            return restore(c, target, snap, onEvent) // nothing to split: full path inside the target
        }
        return restoreFolderPairs(c, chosen.pathList.map { chosen.id to it }, target, rule, onEvent)
    }

    /**
     * Restores the chosen folders, each from the snapshot given with it. [target] "/" = back into its own original
     * place, anything else = a subfolder of that folder. [rule] is the already built `--overwrite` argument list.
     */
    fun restoreFolderPairs(
        c: Config, pairs: List<Pair<String, String>>, target: String, rule: List<String>, onEvent: (ResticEvent) -> Unit,
    ): Int {
        val inPlace = target == "/" || target.isBlank()
        val real = ArrayList<String>()
        val watch: (ResticEvent) -> Unit = { e ->
            if (e is ResticEvent.Error && !isHarmlessRestoreError(e.message)) real += e.message
            onEvent(e)
        }
        val dest = if (inPlace) pairs.map { it.second to it.second } else restoreDestinations(target, pairs.map { it.second })
        var code = 0
        pairs.forEachIndexed { i, (snapId, from) ->
            val to = dest[i].second
            onEvent(ResticEvent.Other("restoring $from" + if (inPlace) "" else " into $to"))
            val r = stream(
                c, restic,
                resticArgs(c, "restore", "$snapId:$from", "--json", "--target", to) + rule,
            ) { watch(ResticJson.parse(it)) }
            // Exit 1 with only the known harmless messages (timestamps / ownership on /sdcard) is a good restore.
            if (r != 0 && !(r == 1 && real.isEmpty())) code = r
        }
        return code
    }

    /** Restores folders picked one by one (each from its own newest backup). */
    fun restoreFolderItems(
        c: Config, items: List<Pair<String, String>>, target: String, overwrite: String, onEvent: (ResticEvent) -> Unit,
    ): Int {
        val rule = if (overwrite in listOf("if-changed", "if-newer", "never")) listOf("--overwrite", overwrite) else emptyList()
        return restoreFolderPairs(c, items, target, rule, onEvent)
    }

    fun restore(c: Config, target: String, snap: String, onEvent: (ResticEvent) -> Unit): Int {
        val filter = if (snap == "latest") listOf("--tag", "android-folders") else emptyList()
        return stream(
            c, restic,
            resticArgs(c, "restore", snap, "--json", "--host", "android", "--target", target) + filter,
        ) { onEvent(ResticJson.parse(it)) }
    }

    /** Backs up several [paths] in one snapshot that carries all of [tags]. */
    fun backupPaths(
        c: Config, paths: List<String>, tags: List<String>, excludes: List<String>, onEvent: (ResticEvent) -> Unit,
    ): Int = stream(
        c, restic,
        resticArgs(c, "backup", "--json", "--host", "android") + tags.flatMap { listOf("--tag", it) } +
            excludes.flatMap { listOf("--exclude", it) } + paths,
    ) { onEvent(ResticJson.parse(it)) }

    // ---- apps --------------------------------------------------------------

    private fun rootSh(script: String): String {
        val p = try {
            ProcessBuilder("su", "--mount-master", "-c", script).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            return "root is not available: ${e.message}"
        }
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    /** All snapshots of host "android" (plus any [extra] restic filters), oldest first. */
    fun listSnapshots(c: Config, vararg extra: String): List<SnapInfo> {
        val wantsTags = "android-tags" in extra
        val sb = StringBuilder()
        val code = stream(c, restic, resticArgs(c, "snapshots", "--json", "-q", "--host", "android") + extra) {
            sb.appendLine(it)
        }
        val line = sb.toString().lines().firstOrNull { it.trimStart().startsWith("[") }
            ?: error("could not list snapshots (exit $code): ${sb.toString().trim().take(300)}")
        val arr = JSONArray(line)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val t = o.optJSONArray("tags")
            val sum = o.optJSONObject("summary")
            SnapInfo(
                id = o.getString("id"),
                time = o.optString("time"),
                tags = if (t == null) emptyList() else (0 until t.length()).map { t.getString(it) },
                paths = o.optJSONArray("paths")?.length() ?: 0,
                totalBytes = sum?.optLong("total_bytes_processed") ?: 0L,
                dataAdded = sum?.optLong("data_added") ?: 0L,
                files = sum?.optLong("total_files_processed") ?: 0L,
                pathList = o.optJSONArray("paths")?.let { p -> (0 until p.length()).map { p.getString(it) } } ?: emptyList(),
            )
        }.filter { wantsTags || "android-tags" !in it.tags }.sortedBy { it.time }
    }

    /**
     * Tags live in the repository as their own tiny snapshot (tag "android-tags"), so a wiped phone gets them back.
     * Only the newest few are kept. Normal snapshot lists never show them.
     */
    fun pushTags(c: Config, json: String): Boolean {
        val f = File(ctx.filesDir, "tags-sync/tags.json")
        f.parentFile?.mkdirs()
        f.writeText(json)
        val code = stream(
            c, restic,
            resticArgs(c, "backup", "--json", "--host", "android", "--tag", "android-tags", f.absolutePath),
        ) { }
        if (code != 0) return false
        stream(c, restic, resticArgs(c, "forget", "--host", "android", "--tag", "android-tags", "--keep-last", "3")) { }
        return true
    }

    /** The newest saved tag file as text; null when the repository has none yet. Throws when it cannot be read. */
    fun pullTags(c: Config): String? {
        val newest = listSnapshots(c, "--tag", "android-tags").lastOrNull() ?: return null
        val ls = StringBuilder()
        stream(c, restic, resticArgs(c, "ls", "-q", newest.id)) { ls.appendLine(it) }
        val path = ls.lines().map { it.trim() }.firstOrNull { it.endsWith("/tags-sync/tags.json") } ?: return null
        val sb = StringBuilder()
        val code = stream(c, restic, resticArgs(c, "dump", "-q", newest.id, path)) { sb.appendLine(it) }
        val t = sb.toString()
        if (code != 0 || '{' !in t) error("could not read the saved tags (exit $code)")
        return t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1)
    }

    /**
     * The apps stored in one android-apps snapshot (from its manifest.json). Uses the same on-phone cache as
     * [readManifest], so after the first read this needs no network. Returns null if the manifest can't be read.
     */
    fun snapshotApps(c: Config, snap: SnapInfo, onProblem: (String) -> Unit = {}): List<AppInfo>? {
        val cacheDir = File(ctx.filesDir, "manifest-cache").apply { mkdirs() }
        val cached = File(cacheDir, snap.id)
        var text = if (cached.exists()) cached.readText() else ""
        if (text.isEmpty()) {
            val path = manifestPathIn(snap.pathList, File(ctx.filesDir, "app-manifest/manifest.json").absolutePath)
            val sb = StringBuilder()
            stream(c, restic, resticArgs(c, "dump", "-q", snap.id, path)) { sb.appendLine(it) }
            val t = sb.toString()
            if ('{' !in t) {
                onProblem(t.trim().lines().firstOrNull().orEmpty().take(200).ifEmpty { "nothing came back" })
                return null
            }
            text = t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1)
        }
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (!cached.exists()) cached.writeText(text)
        val arr = obj.optJSONArray("apps") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val apks = o.optJSONArray("apks")
            val permsArr = o.optJSONArray("perms")
            val compsArr = o.optJSONArray("components")
            AppInfo(
                pkg = o.getString("package"),
                label = o.optString("label", o.getString("package")),
                version = o.optString("version"),
                uid = o.optInt("uid"),
                apks = if (apks == null) emptyList() else (0 until apks.length()).map { apks.getString(it) },
                snapshot = snap.id,
                perms = if (permsArr == null) emptyList() else (0 until permsArr.length()).map { permsArr.getString(it) },
                backedUpAt = snap.time.take(16).replace('T', ' '),
                components = if (compsArr == null) emptyList() else (0 until compsArr.length()).map { compsArr.getString(it) },
            )
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Bytes stored under each of [roots] in snapshot [snapId] (one `restic ls`). A root that is in the
     * snapshot but holds no files maps to 0; a root that is not in the snapshot is missing from the map.
     * Snapshots never change, so the result is cached on the phone.
     */
    fun snapshotSizes(c: Config, snapId: String, roots: List<String>): Map<String, Long> {
        val cacheDir = File(ctx.filesDir, "size-cache").apply { mkdirs() }
        val cached = File(cacheDir, snapId)
        val wanted = roots.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.toSet()
        if (cached.exists()) {
            val o = runCatching { JSONObject(cached.readText()) }.getOrNull()
            if (o != null && wanted.all { o.has(it) || o.has("?$it") }) {
                return wanted.filter { o.has(it) }.associateWith { o.getLong(it) }
            }
        }
        val sizes = HashMap<String, Long>()
        val code = stream(c, restic, resticArgs(c, "ls", "--json", "-q", snapId)) { line ->
            if (!line.startsWith("{") || "\"node\"" !in line) return@stream
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@stream
            var p = o.optString("path")
            val size = if (o.optString("type") == "file") o.optLong("size") else 0L
            // Walk up the path until it hits one of the roots.
            while (p.isNotEmpty()) {
                if (p in wanted) {
                    sizes[p] = (sizes[p] ?: 0L) + size
                    break
                }
                val cut = p.lastIndexOf('/')
                if (cut <= 0) break
                p = p.substring(0, cut)
            }
        }
        if (code != 0) error("restic ls failed (exit $code)")
        // Remember the misses too ("?root"), so a cached answer is complete.
        val out = JSONObject()
        for (r in wanted) if (r in sizes) out.put(r, sizes[r]) else out.put("?$r", 0)
        runCatching { cached.writeText(out.toString()) }
        return sizes
    }

    /**
     * Merges the app lists of ALL android-apps snapshots (newest wins) and remembers each app's snapshot.
     * Snapshots never change, so each manifest is cached on the phone after the first read.
     */
    fun readManifest(c: Config, onProblem: (String) -> Unit = {}): List<AppInfo> {
        val snaps = listSnapshots(c, "--tag", "android-apps").reversed() // newest first
        val cacheDir = File(ctx.filesDir, "manifest-cache").apply { mkdirs() }
        val live = snaps.map { it.id }.toSet()
        cacheDir.listFiles()?.filter { it.name !in live }?.forEach { it.delete() }

        val ownPath = File(ctx.filesDir, "app-manifest/manifest.json").absolutePath
        val found = LinkedHashMap<String, AppInfo>()
        for (snap in snaps) {
            val path = manifestPathIn(snap.pathList, ownPath)
            val cached = File(cacheDir, snap.id)
            var text = if (cached.exists()) cached.readText() else ""
            if (text.isEmpty()) {
                val sb = StringBuilder()
                stream(c, restic, resticArgs(c, "dump", "-q", snap.id, path)) { sb.appendLine(it) }
                val t = sb.toString()
                if ('{' !in t) {
                    onProblem("backup ${snap.id.take(8)}: could not read its app list (${t.trim().lines().firstOrNull().orEmpty().take(160)})")
                    continue
                }
                text = t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1)
            }
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: run {
                onProblem("backup ${snap.id.take(8)}: its app list is damaged")
                null
            } ?: continue
            if (!cached.exists()) cached.writeText(text)
            val arr = obj.optJSONArray("apps") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkg = o.getString("package")
                if (pkg in found) continue
                val apks = o.getJSONArray("apks")
                val permsArr = o.optJSONArray("perms")
                found[pkg] = AppInfo(
                    pkg = pkg,
                    label = o.getString("label"),
                    version = o.optString("version"),
                    uid = o.optInt("uid"),
                    apks = (0 until apks.length()).map { apks.getString(it) },
                    snapshot = snap.id,
                    perms = if (permsArr == null) emptyList() else (0 until permsArr.length()).map { permsArr.getString(it) },
                    backedUpAt = snap.time.take(16).replace('T', ' '),
                )
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /**
     * Keeps the newest [keep] snapshots of each kind (same tags). A snapshot that is the newest copy of
     * some app is kept even if it is older, so pruning can never remove an app's only backup.
     */
    fun planPrune(c: Config, policy: RetentionPolicy): PrunePlan {
        val all = listSnapshots(c)
        val newest = readManifest(c) // throws on failure, which aborts the prune safely
        val needed = newest.map { it.snapshot }.toSet()
        val delete = ArrayList<SnapInfo>()
        val shielded = LinkedHashSet<String>()
        for ((_, group) in all.groupBy { it.tags.filter { t -> !t.startsWith("android-count-") }.sorted().joinToString(",") }) {
            val keep = Retention.keep(group.mapNotNull { s -> parseResticTime(s.time)?.let { s.id to it } }, policy)
            for (s in group) {
                if (s.id in keep) continue
                if (parseResticTime(s.time) == null) continue // unknown time: never delete
                if (s.id in needed) shielded += s.id else delete += s
            }
        }
        val labels = newest.filter { it.snapshot in shielded }.map { it.label }
        return PrunePlan(delete, shielded.size, labels)
    }

    /**
     * Backs up contacts, call log and/or messages. Each kind is read from the system into a file in the app's folder,
     * saved as its own snapshot (tag per kind, and the number of entries), and the file is deleted again.
     */
    fun backupPhone(c: Config, kinds: List<PhoneKind>, onEvent: (ResticEvent) -> Unit): Int {
        var code = 0
        for (k in kinds) {
            val n = try {
                PhoneData.export(ctx, k)
            } catch (e: Exception) {
                onEvent(ResticEvent.Error("${k.label}: ${e.message}"))
                PhoneData.file(ctx, k).delete()
                code = 1
                continue
            }
            onEvent(ResticEvent.Other(if (k == PhoneKind.WALLPAPER) "${k.label}: $n image(s) read" else "${k.label}: ${n} entries read"))
            val file = PhoneData.file(ctx, k)
            if (n == 0) {
                onEvent(ResticEvent.Other("${k.label}: nothing to save"))
                file.delete()
                continue
            }
            try {
                val r = backupPaths(c, listOf(file.absolutePath), listOf(k.tag, "android-count-$n"), emptyList(), onEvent)
                if (r != 0) code = r
            } finally {
                file.delete() // personal data does not stay lying around in the app's folder
            }
        }
        return code
    }

    /**
     * Restores one kind from [snap] ("latest" = the newest backup of that kind). The saved file is read out of the
     * repository into this app's cache and its entries are added; what the phone already has is skipped.
     */
    fun restorePhone(c: Config, kind: PhoneKind, snap: String, onEvent: (ResticEvent) -> Unit): Int {
        if (!PhoneData.canWrite(ctx, kind)) {
            onEvent(ResticEvent.Error(
                if (kind == PhoneKind.SMS) "this app is not the default messaging app, so Android does not let it write messages"
                else "${kind.label}: permission to write is missing"
            ))
            return 1
        }
        val all = listSnapshots(c, "--tag", kind.tag)
        val chosen = if (snap == "latest") all.lastOrNull() else all.firstOrNull { it.id == snap }
        if (chosen == null || chosen.pathList.isEmpty()) {
            onEvent(ResticEvent.Error("no ${kind.label} backup found in the repository"))
            return 1
        }
        val out = File(ctx.cacheDir, "phone-restore-" + kind.file)
        try {
            val code = out.outputStream().use { streamBytes(c, restic, resticArgs(c, "dump", "-q", chosen.id, chosen.pathList.first()), it) }
            if (code != 0 || out.length() == 0L) {
                onEvent(ResticEvent.Error("${kind.label}: could not read the backup (exit $code)"))
                return if (code != 0) code else 1
            }
            val r = PhoneData.import(ctx, kind, out)
            onEvent(ResticEvent.Other(
                if (kind == PhoneKind.WALLPAPER) "${kind.label}: ${r.added} set"
                else "${kind.label}: ${r.added} added, ${r.skipped} were already on the phone"
            ))
            if (kind == PhoneKind.SMS) JobHub.restoredSms = true
            return 0
        } catch (e: Exception) {
            onEvent(ResticEvent.Error("${kind.label}: ${e.message}"))
            return 1
        } finally {
            out.delete()
        }
    }

    /** Restores each app from the newest snapshot that contains it. */
    fun restoreApps(c: Config, apps: List<AppInfo>, comps: Set<Component>, onEvent: (ResticEvent) -> Unit): Int {
        var code = 0
        for ((snap, group) in apps.groupBy { it.snapshot.ifEmpty { "latest" } }) {
            val r = restoreGroup(c, snap, group, if (c.useRoot) comps else setOf(Component.APK), onEvent)
            if (r != 0) code = r
        }
        return code
    }

    private fun restoreGroup(
        c: Config, snap: String, apps: List<AppInfo>, comps: Set<Component>, onEvent: (ResticEvent) -> Unit,
    ): Int {
        val base = arrayOf("restore", snap, "--json")
        fun inc(paths: List<String>) = paths.flatMap { listOf("--include", it) }
        var code = 0

        if (Component.APK in comps && !c.useRoot && BuildConfig.PLAY) {
            // Google Play does not allow this app to install apps, so without root there is nothing it can do here.
            onEvent(ResticEvent.Error("restoring apps without root is not available in this version of the app"))
            return 1
        }
        if (Component.APK in comps && !c.useRoot) {
            // No root: restore the APK files into the app's own cache, then hand them to Android's installer.
            val stage = File(ctx.cacheDir, "apk-stage")
            stage.deleteRecursively()
            code = stream(c, restic, resticArgs(c, *base, "--target", stage.absolutePath) + inc(apps.flatMap { Component.APK.paths(it) })) {
                onEvent(ResticJson.parse(it))
            }
            val found = stage.walkTopDown().filter { it.isFile && it.name.endsWith(".apk") }.toList()
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                onEvent(ResticEvent.Other("Allow \"Install unknown apps\" for this app, then restore again."))
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:${ctx.packageName}"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                stage.deleteRecursively()
                return 1
            }
            for (a in apps) {
                val files = found.filter { "/${a.pkg}-" in it.path }
                if (files.isEmpty()) {
                    onEvent(ResticEvent.Other("no APKs found for ${a.pkg}"))
                    continue
                }
                onEvent(ResticEvent.Other("${a.pkg}: " + runCatching { ApkInstaller.install(ctx, files) }.getOrElse { "install failed: ${it.message}" }))
            }
            stage.deleteRecursively() // the installer keeps its own copy once the session is committed
            onEvent(ResticEvent.Other("confirm each install on the screen. Without root, app data is not restored."))
            return code
        }

        if (Component.APK in comps) {
            val stage = "/data/local/tmp/restic-apk-stage"
            rootSh("rm -rf $stage")
            code = stream(c, restic, resticArgs(c, *base, "--target", stage) + inc(apps.flatMap { Component.APK.paths(it) })) {
                onEvent(ResticJson.parse(it))
            }
            val found = rootSh("find $stage -name '*.apk'").lines().filter { it.endsWith(".apk") }
            for (a in apps) {
                val files = found.filter { "/${a.pkg}-" in it }
                if (files.isEmpty()) {
                    onEvent(ResticEvent.Other("no APKs found for ${a.pkg}"))
                    continue
                }
                onEvent(ResticEvent.Other("installing ${a.pkg} (${files.size} apk)"))
                onEvent(ResticEvent.Other(installApks(files)))
            }
            rootSh("rm -rf $stage")
        }

        val dataComps = comps - Component.APK
        if (dataComps.isNotEmpty()) {
            val labels = apps.associate { a ->
                a.pkg to listOf("/data/data/${a.pkg}", "/data/user_de/0/${a.pkg}")
                    .associateWith { rootSh("stat -c %C '$it' 2>/dev/null").trim() }
            }
            // A VPN app carries this very connection, so it is not stopped or disabled while the restore reads from the repository.
            val vpn = Net.vpnPackages(ctx)
            val stopped = AppBackup.stoppable(apps.map { it.pkg }, ctx.packageName, vpn)
            val kept = apps.map { it.pkg }.filter { it in vpn }
            if (kept.isNotEmpty()) onEvent(ResticEvent.Other("kept running (VPN, the restore uses its connection): ${kept.joinToString()}"))
            rootSh(stopped.joinToString("; ") { "am force-stop '$it'; pm disable-user --user 0 '$it'" })
            try {
                val internal = listOf(Component.DATA, Component.DEVICE_DATA).filter { it in dataComps }
                val external = dataComps - internal.toSet()

                // App data in /data: restore into a temporary folder first. Only when that worked completely is the
                // app's current data moved aside (kept as a safety copy) and replaced. Any failure puts it all back.
                if (internal.isNotEmpty()) {
                    val r = restoreInternalSafely(c, base, apps, internal, onEvent)
                    if (r != 0) code = r
                }

                // Shared storage (Android/data, media, obb): files are put back over what is there.
                if (external.isNotEmpty()) {
                    val paths = apps.flatMap { a -> external.flatMap { it.paths(a) } }
                    val r = stream(c, restic, resticArgs(c, *base, "--target", "/") + inc(paths)) {
                        onEvent(ResticJson.parse(it))
                    }
                    if (r > 1) code = r // 1 = harmless /sdcard timestamp errors
                }

                for (a in apps) {
                    val uid = runCatching { ctx.packageManager.getApplicationInfo(a.pkg, 0).uid }.getOrNull()
                    if (uid == null) {
                        onEvent(ResticEvent.Other("skipped ${a.pkg}: not installed"))
                        continue
                    }
                    val sb = StringBuilder()
                    for ((dir, label) in labels[a.pkg].orEmpty()) {
                        // top dir + everything except cache dirs (those keep their special cache gid)
                        sb.appendLine("[ -e '$dir' ] && chown $uid:$uid '$dir'")
                        sb.appendLine(
                            "[ -d '$dir' ] && find '$dir' -mindepth 1 -maxdepth 1 ! -name cache ! -name code_cache " +
                                "-exec chown -hR $uid:$uid {} +"
                        )
                        sb.appendLine(
                            if (label.startsWith("u:")) "[ -e '$dir' ] && chcon -hR '$label' '$dir'"
                            else "[ -e '$dir' ] && restorecon -RF '$dir'"
                        )
                        sb.appendLine("ls -ldZ '$dir'")
                    }
                    onEvent(ResticEvent.Other("fixed ${a.pkg} (wanted label: ${labels[a.pkg]?.values}):\n" + rootSh(sb.toString())))
                }
            } finally {
                rootSh(stopped.joinToString("; ") { "pm enable '$it'" })
            }
        }

        // Re-grant runtime permissions that were granted at backup time.
        for (a in apps) {
            if (a.perms.isEmpty()) continue
            val installed = runCatching { ctx.packageManager.getApplicationInfo(a.pkg, 0) }.isSuccess
            if (!installed) continue
            rootSh(a.perms.joinToString("; ") { "pm grant --user 0 '${a.pkg}' '$it' >/dev/null 2>&1" })
            onEvent(ResticEvent.Other("re-granted ${a.perms.size} permissions for ${a.pkg}"))
        }
        return code
    }

    private val stageRoot = "/data/local/tmp/restic-data-stage"
    private val undoRoot = "/data/local/tmp/restic-data-undo"

    /** The swap script from the app's resources, written where root can run it. */
    private fun swapScript(): File {
        val f = File(ctx.filesDir, "swap_data.sh")
        ctx.resources.openRawResource(R.raw.swap_data).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    /**
     * Restores app data from /data in two steps: first everything is restored into a temporary folder (a failure here
     * leaves the phone untouched), then each app's current data is moved to a safety copy and the restored data moved
     * in. Returns 0 when all went well.
     */
    private fun restoreInternalSafely(
        c: Config, base: Array<out String>, apps: List<AppInfo>, comps: List<Component>, onEvent: (ResticEvent) -> Unit,
    ): Int {
        rootSh("rm -rf '$stageRoot'; mkdir -p '$stageRoot'; chmod 700 '$stageRoot'")
        try {
            val include = apps.flatMap { a -> comps.flatMap { it.paths(a) } }.flatMap { listOf("--include", it) }
            val r = stream(c, restic, resticArgs(c, *base, "--target", stageRoot) + include) { onEvent(ResticJson.parse(it)) }
            if (r != 0) {
                onEvent(ResticEvent.Other("the backup could not be read completely (exit $r). Your current app data was not touched."))
                return r
            }
            val script = swapScript().absolutePath
            var result = 0
            for (a in apps) {
                if (runCatching { ctx.packageManager.getApplicationInfo(a.pkg, 0) }.isFailure) continue // reported later
                rootSh("rm -rf '$undoRoot/${a.pkg}'") // the safety copy of an earlier restore of this app
                for (comp in comps) {
                    val live = comp.paths(a).first()
                    val staged = stageRoot + live
                    if (rootSh("[ -d '$staged' ] && echo yes") != "yes") {
                        onEvent(ResticEvent.Other("${a.pkg}: no ${comp.label} in this backup, left as it is"))
                        continue
                    }
                    val undo = "$undoRoot/${a.pkg}/" + if (comp == Component.DATA) "data" else "device"
                    val out = rootSh("sh '$script' '$live' '$staged' '$undo' 2>&1")
                    if (out.lines().any { it.trim() == "SWAP_OK" }) {
                        onEvent(ResticEvent.Other("${a.pkg}: ${comp.label} replaced. The data it had before is kept in $undo"))
                    } else {
                        onEvent(ResticEvent.Other("${a.pkg}: ${comp.label} could not be replaced, your data was put back ($out)"))
                        result = 1
                    }
                }
            }
            return result
        } finally {
            rootSh("rm -rf '$stageRoot'")
        }
    }

    /** Deletes the safety copies. */
    fun clearRestoreCopies(): String = rootSh("rm -rf '$undoRoot'; echo done")

    /** Installs one app (base + splits) with a standard pm session. Works on any Android 5.0+. */
    private fun installApks(files: List<String>): String {
        val out = StringBuilder()
        val created = rootSh("pm install-create -r -d")
        val sid = Regex("\\[(\\d+)]").find(created)?.groupValues?.get(1)
            ?: return "install-create failed: $created"
        for (f in files) {
            val size = rootSh("wc -c < '$f'").trim()
            out.appendLine(rootSh("pm install-write -S $size $sid '${File(f).name}' '$f'"))
        }
        out.appendLine(rootSh("pm install-commit $sid"))
        return out.toString().trim()
    }
}
