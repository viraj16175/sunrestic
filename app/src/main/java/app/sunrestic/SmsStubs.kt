package app.sunrestic

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.provider.Telephony

/*
 * Android lets only the default messaging app write text messages. To restore messages, this app asks to become the
 * default for a short moment. A messaging app must have these four parts, so they are here. They are deliberately
 * minimal: this is not a messaging app. The one thing that matters is that a text arriving during that moment is
 * saved into the phone's messages, so it is not lost.
 */

/** Receives every incoming text while this app is the default, and files it into the inbox. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, parts[0].originatingAddress.orEmpty())
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, parts[0].timestampMillis)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        runCatching { ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }
    }
}

/** Picture messages are not handled here. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Unit
}

/** Required of a messaging app; does nothing. */
class SmsComposeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

/** Required of a messaging app; does nothing. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
