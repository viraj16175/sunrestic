package app.sunrestic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** "All files access" (Android 11+): lets the app read and write the phone's folders without root. */
object Storage {
    /** True when the app may reach /sdcard. Always false before Android 11, where only root can. */
    fun hasAllFiles(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** Opens the system screen where the user switches "All files access" on for this app. */
    fun openSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (i in tries) if (runCatching { ctx.startActivity(i.addFlags(flags)) }.isSuccess) return
    }
}
