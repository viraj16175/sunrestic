package app.sunrestic

import org.json.JSONObject

sealed interface ResticEvent {
    data class Status(
        val percent: Double,
        val filesDone: Long,
        val totalFiles: Long,
        val bytesDone: Long,
        val totalBytes: Long,
        val secondsRemaining: Long?,
    ) : ResticEvent

    data class Summary(
        val snapshotId: String,
        val filesNew: Long,
        val filesChanged: Long,
        val dataAdded: Long,
        val totalFiles: Long,
    ) : ResticEvent

    data class RestoreSummary(
        val filesRestored: Long,
        val bytesRestored: Long,
        val seconds: Double,
    ) : ResticEvent

    data class Error(val message: String) : ResticEvent
    data class Other(val raw: String) : ResticEvent
}

/** Parses one line of `restic ... --json` output (backup and restore). */
object ResticJson {
    fun parse(line: String): ResticEvent {
        if (!line.startsWith("{")) return ResticEvent.Other(line)
        return try {
            val o = JSONObject(line)
            when (o.optString("message_type")) {
                "status" -> {
                    val bytesDone = if (o.has("bytes_done")) o.optLong("bytes_done") else o.optLong("bytes_restored")
                    val totalBytes = o.optLong("total_bytes")
                    val pct = when {
                        o.has("percent_done") -> o.optDouble("percent_done", 0.0)
                        totalBytes > 0 -> bytesDone.toDouble() / totalBytes
                        else -> 0.0
                    }
                    val elapsed = o.optDouble("seconds_elapsed", 0.0)
                    val eta = when {
                        o.has("seconds_remaining") -> o.optLong("seconds_remaining")
                        pct > 0.02 && elapsed > 0 -> (elapsed * (1 - pct) / pct).toLong()
                        else -> null
                    }
                    ResticEvent.Status(
                        percent = pct,
                        filesDone = if (o.has("files_done")) o.optLong("files_done") else o.optLong("files_restored"),
                        totalFiles = o.optLong("total_files"),
                        bytesDone = bytesDone,
                        totalBytes = totalBytes,
                        secondsRemaining = eta,
                    )
                }
                "summary" ->
                    if (!o.has("snapshot_id")) ResticEvent.RestoreSummary(
                        filesRestored = o.optLong("files_restored"),
                        bytesRestored = o.optLong("bytes_restored"),
                        seconds = o.optDouble("seconds_elapsed", 0.0),
                    ) else ResticEvent.Summary(
                        snapshotId = o.optString("snapshot_id"),
                        filesNew = o.optLong("files_new"),
                        filesChanged = o.optLong("files_changed"),
                        dataAdded = o.optLong("data_added"),
                        totalFiles = o.optLong("total_files_processed"),
                    )
                "error" -> ResticEvent.Error(o.optJSONObject("error")?.optString("message") ?: line)
                else -> ResticEvent.Other(line)
            }
        } catch (e: Exception) {
            ResticEvent.Other(line)
        }
    }
}

fun fmtBytes(n: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$n B" else "%.1f %s".format(v, units[i])
}

fun fmtEta(seconds: Long?): String = when {
    seconds == null || seconds <= 0 -> ""
    seconds < 60 -> "${seconds}s left"
    else -> "${seconds / 60} min ${seconds % 60}s left"
}
