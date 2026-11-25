package app.sunrestic

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Keeps the repo password encrypted with an AES key that lives in the Android Keystore and
 * needs authentication for every use. Only the ciphertext is stored in SharedPreferences.
 *
 * Android 11+: fingerprint OR the screen-lock PIN/pattern works, and adding a new fingerprint no
 * longer destroys the saved password. Android 9-10: fingerprint only, as before.
 * A key saved by an older version keeps the old rules until you press "Update saved" once.
 */
object Vault {
    private const val ALIAS = "restic_repo_password"
    private const val PREFS = "vault"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun has(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("ct")

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun createKey(): SecretKey {
        val b = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // timeout 0 = authenticate for every single use
            b.setUserAuthenticationParameters(
                0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            b.setInvalidatedByBiometricEnrollment(false)
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(b.build())
        return gen.generateKey()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun prompt(
        activity: Activity,
        title: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onFail: (String) -> Unit,
    ) {
        val executor = activity.mainExecutor
        var finished = false
        fun fail(msg: String) {
            if (finished) return
            finished = true
            onFail(msg)
        }

        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("Restic repository password")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // With device credential allowed the system shows its own "use PIN" button; no negative button.
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            builder.setNegativeButton("Cancel", executor) { _, _ -> fail("Cancelled") }
        }
        val prompt = builder.build()

        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (finished) return
                    val c = result.cryptoObject?.cipher
                    if (c == null) {
                        fail("Authentication returned no cipher")
                    } else {
                        finished = true
                        onSuccess(c)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    fail(errString?.toString() ?: "Authentication error")
                }
            },
        )
    }

    /** Asks for a fingerprint, then stores [password] encrypted. */
    fun save(activity: Activity, password: String, onResult: (ok: Boolean, message: String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onResult(false, "Fingerprint unlock needs Android 9 or newer")
            return
        }
        try {
            runCatching { keyStore().deleteEntry(ALIAS) }
            val key = createKey()
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
            prompt(
                activity, "Save password", cipher,
                onSuccess = { c ->
                    try {
                        val ct = c.doFinal(password.toByteArray(Charsets.UTF_8))
                        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                            .apply()
                        onResult(true, "Password saved")
                    } catch (e: Exception) {
                        onResult(false, "Could not save: ${e.message}")
                    }
                },
                onFail = { onResult(false, it) },
            )
        } catch (e: Exception) {
            // e.g. no screen lock / no enrolled fingerprint
            onResult(false, "Keystore error: ${e.message}")
        }
    }

    /** Asks for a fingerprint, then returns the decrypted password (null on failure). */
    fun unlock(activity: Activity, onResult: (password: String?, message: String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onResult(null, "Fingerprint unlock needs Android 9 or newer")
            return
        }
        val sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = sp.getString("iv", null)
        val ct = sp.getString("ct", null)
        if (iv == null || ct == null) {
            onResult(null, "No saved password")
            return
        }
        try {
            val key = keyStore().getKey(ALIAS, null) as? SecretKey
            if (key == null) {
                clear(activity)
                onResult(null, "Saved key is gone. Enter the password and save it again.")
                return
            }
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            prompt(
                activity, "Unlock password", cipher,
                onSuccess = { c ->
                    try {
                        val pt = c.doFinal(Base64.decode(ct, Base64.NO_WRAP))
                        onResult(String(pt, Charsets.UTF_8), "Unlocked")
                    } catch (e: Exception) {
                        onResult(null, "Could not decrypt: ${e.message}")
                    }
                },
                onFail = { onResult(null, it) },
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear(activity)
            onResult(null, "Fingerprints changed. Enter the password and save it again.")
        } catch (e: Exception) {
            onResult(null, "Keystore error: ${e.message}")
        }
    }

    // ---- unattended copy: no fingerprint, only exists when the user turns on automatic backups ----

    private const val BG_ALIAS = "restic_repo_password_bg"
    private const val BG_PREFS = "vault_bg"

    fun hasUnattended(ctx: Context): Boolean =
        ctx.getSharedPreferences(BG_PREFS, Context.MODE_PRIVATE).contains("ct")

    fun clearUnattended(ctx: Context) {
        ctx.getSharedPreferences(BG_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { keyStore().deleteEntry(BG_ALIAS) }
    }

    /** Encrypts the password with a Keystore key that needs no authentication, so a background job can read it. */
    fun saveUnattended(ctx: Context, password: String): Boolean = runCatching {
        runCatching { keyStore().deleteEntry(BG_ALIAS) }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(BG_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, gen.generateKey())
        val ct = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        ctx.getSharedPreferences(BG_PREFS, Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
        true
    }.getOrDefault(false)

    fun loadUnattended(ctx: Context): String? = runCatching {
        val sp = ctx.getSharedPreferences(BG_PREFS, Context.MODE_PRIVATE)
        val iv = Base64.decode(sp.getString("iv", null), Base64.NO_WRAP)
        val ct = Base64.decode(sp.getString("ct", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, keyStore().getKey(BG_ALIAS, null) as SecretKey, GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()
}
