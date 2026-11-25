package app.sunrestic

import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.mutableStateOf
import kotlin.reflect.KProperty

/** A String state that is written to SharedPreferences on every change. Use with `by`. */
class PersistedString(private val prefs: SharedPreferences, private val key: String, default: String) {
    private val state = mutableStateOf(prefs.getString(key, default) ?: default)

    operator fun getValue(thisRef: Any?, property: KProperty<*>): String = state.value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
        state.value = value
        prefs.edit().putString(key, value).apply()
    }
}

/** A Boolean state that is written to SharedPreferences on every change. Use with `by`. */
class PersistedBool(private val prefs: SharedPreferences, private val key: String, default: Boolean) {
    private val state = mutableStateOf(prefs.getBoolean(key, default))

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = state.value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        state.value = value
        prefs.edit().putBoolean(key, value).apply()
    }
}

/** An Int state that is written to SharedPreferences on every change. Use with `by`. */
class PersistedInt(private val prefs: SharedPreferences, private val key: String, default: Int) {
    private val state = mutableStateOf(prefs.getInt(key, default))

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = state.value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        state.value = value
        prefs.edit().putInt(key, value).apply()
    }
}

/**
 * Turns a folder-picker (SAF) tree URI into a real path. Backups run as root, so the path is
 * what matters, not the SAF permission. Returns null if the URI can't be mapped.
 */
fun treeUriToPath(uri: Uri): String? {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val volume = id.substringBefore(':')
    val rel = id.substringAfter(':', "").trim('/')
    val base = when (volume) {
        "primary" -> "/sdcard"
        "home" -> "/sdcard/Documents"
        else -> "/storage/$volume"
    }
    return if (rel.isEmpty()) base else "$base/$rel"
}
