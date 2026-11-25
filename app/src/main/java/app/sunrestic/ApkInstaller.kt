package app.sunrestic

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/** Installs APKs without root through Android's own installer: the system asks you to confirm each app. */
object ApkInstaller {
    private const val ACTION = "app.sunrestic.INSTALL_RESULT"

    fun install(ctx: Context, files: List<File>): String {
        val installer = ctx.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { session ->
            for (f in files) {
                f.inputStream().use { input ->
                    session.openWrite(f.name, 0, f.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            }
            val result = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(result.intentSender)
        }
        return "asked Android to install ${files.size} APK file(s)"
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> JobHub.append("installed\n")
            else -> JobHub.append("install failed: $msg\n")
        }
    }
}
