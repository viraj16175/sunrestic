package app.sunrestic

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.ByteArrayInputStream
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import java.io.File

/** The kinds of phone data (contacts, call log, messages, wallpaper) that can be backed up without root. Each kind is its own snapshot in the repository. */
enum class PhoneKind(
    val tag: String,
    val label: String,
    val file: String,
    val read: Array<String>,
    val write: Array<String>,
) {
    CONTACTS(
        "android-contacts", "Contacts", "contacts.json",
        arrayOf(Manifest.permission.READ_CONTACTS), arrayOf(Manifest.permission.WRITE_CONTACTS),
    ),
    CALLS(
        "android-calls", "Call log", "calls.json",
        arrayOf(Manifest.permission.READ_CALL_LOG), arrayOf(Manifest.permission.WRITE_CALL_LOG),
    ),
    // Writing messages is not a permission: only the default messaging app may do it (see SmsStubs.kt).
    SMS("android-sms", "Messages", "sms.json", arrayOf(Manifest.permission.READ_SMS), emptyArray()),
    // Reading the wallpaper needs "All files access" (checked when the backup starts); setting it needs only SET_WALLPAPER,
    // a permission Android grants at install.
    WALLPAPER("android-wallpaper", "Wallpaper", "wallpaper.json", emptyArray(), emptyArray());

    companion object {
        /** The kind a snapshot holds, from its tags (null = not a phone data snapshot). */
        fun of(tags: List<String>): PhoneKind? = values().firstOrNull { it.tag in tags }
    }
}

data class ImportResult(val added: Int, val skipped: Int)

// ---- how two entries are recognised as the same one (so a restore never doubles what is already there) ----

/** The last ten digits, so +91 98765 43210 and 098765-43210 are the same number. */
internal fun digitsOnly(s: String?): String = s.orEmpty().filter { it.isDigit() }.takeLast(10)

internal fun callKey(number: String?, date: Long, type: Int) = "${digitsOnly(number)}|$date|$type"

internal fun smsKey(address: String?, date: Long, type: Int, body: String?): String {
    val who = digitsOnly(address).ifEmpty { address.orEmpty().trim().lowercase() } // a name like "BANK" has no digits
    return "$who|$date|$type|${body.orEmpty().hashCode()}"
}

internal fun contactKey(name: String?, phones: Collection<String>) =
    name.orEmpty().trim().lowercase() + "|" + phones.map { digitsOnly(it) }.filter { it.isNotEmpty() }.toSortedSet().joinToString(",")

/** Reads phone data from the system and writes it back, without root. Files live in the app's own folder. */
object PhoneData {
    fun dir(ctx: Context) = File(ctx.filesDir, "phone-data")
    fun file(ctx: Context, kind: PhoneKind) = File(dir(ctx), kind.file)

    private fun has(ctx: Context, perms: Array<String>) =
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    fun canRead(ctx: Context, kind: PhoneKind) = has(ctx, kind.read)
    fun isDefaultSms(ctx: Context) = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName
    fun canWrite(ctx: Context, kind: PhoneKind) = if (kind == PhoneKind.SMS) isDefaultSms(ctx) else has(ctx, kind.write)

    /** Permissions to ask for before a backup / before a restore (SMS restore is the role, not a permission). */
    fun missingForBackup(ctx: Context, kinds: Collection<PhoneKind>): List<String> =
        kinds.flatMap { it.read.toList() }.distinct().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

    fun missingForRestore(ctx: Context, kinds: Collection<PhoneKind>): List<String> =
        kinds.flatMap { (it.read + it.write).toList() }.distinct().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

    /** How many entries are on this phone right now; null when the permission is missing. */
    fun liveCount(ctx: Context, kind: PhoneKind): Int? {
        if (!canRead(ctx, kind)) return null
        val cr = ctx.contentResolver
        return runCatching {
            val uri = when (kind) {
                PhoneKind.CONTACTS -> ContactsContract.RawContacts.CONTENT_URI
                PhoneKind.CALLS -> CallLog.Calls.CONTENT_URI
                PhoneKind.SMS -> Telephony.Sms.CONTENT_URI
                PhoneKind.WALLPAPER -> return if (Storage.hasAllFiles()) 1 else null // always one wallpaper once it can be read
            }
            val sel = if (kind == PhoneKind.CONTACTS) "${ContactsContract.RawContacts.DELETED}=0" else null
            cr.query(uri, arrayOf("_id"), sel, null, null)?.use { it.count }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ backup

    /** Writes the kind to its file and returns how many entries it holds. */
    fun export(ctx: Context, kind: PhoneKind): Int {
        dir(ctx).mkdirs()
        var n = 0
        JsonWriter(file(ctx, kind).bufferedWriter(Charsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("app").value("sunrestic")
            w.name("version").value(1L)
            w.name("kind").value(kind.name)
            w.name("items")
            w.beginArray()
            n = when (kind) {
                PhoneKind.CONTACTS -> writeContacts(ctx, w)
                PhoneKind.CALLS -> writeCalls(ctx, w)
                PhoneKind.SMS -> writeSms(ctx, w)
                PhoneKind.WALLPAPER -> writeWallpapers(ctx, w)
            }
            w.endArray()
            w.name("count").value(n.toLong())
            w.endObject()
        }
        return n
    }

    private fun writeCalls(ctx: Context, w: JsonWriter): Int {
        val cols = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.CACHED_NUMBER_TYPE, CallLog.Calls.CACHED_NUMBER_LABEL, CallLog.Calls.COUNTRY_ISO, CallLog.Calls.NUMBER_PRESENTATION,
        )
        var n = 0
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, null, null, "${CallLog.Calls.DATE} ASC")?.use { c ->
            while (c.moveToNext()) {
                w.beginObject()
                w.name("number").value(c.getString(0).orEmpty())
                w.name("date").value(c.getLong(1))
                w.name("duration").value(c.getLong(2))
                w.name("type").value(c.getInt(3).toLong())
                w.name("name").value(c.getString(4).orEmpty())
                w.name("numbertype").value(c.getInt(5).toLong())
                w.name("numberlabel").value(c.getString(6).orEmpty())
                w.name("countryiso").value(c.getString(7).orEmpty())
                w.name("presentation").value(c.getInt(8).toLong())
                w.endObject()
                n++
            }
        }
        return n
    }

    /** The home screen wallpaper and, when it has its own, the lock screen one, each as a base64 image. */
    private fun writeWallpapers(ctx: Context, w: JsonWriter): Int {
        val wm = WallpaperManager.getInstance(ctx)
        var n = 0
        for ((name, which) in listOf("system" to WallpaperManager.FLAG_SYSTEM, "lock" to WallpaperManager.FLAG_LOCK)) {
            val pfd = try {
                wm.getWallpaperFile(which)
            } catch (e: SecurityException) {
                throw IllegalStateException("Android did not let the app read the wallpaper. Allow All files access (Back up folders page).")
            } ?: continue // no wallpaper of this kind (for instance no separate lock screen one)
            val bytes = pfd.use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            if (bytes.isEmpty()) continue
            if (bytes.size > 40_000_000) throw IllegalStateException("the $name wallpaper is too large (${bytes.size / 1_000_000} MB)")
            w.beginObject()
            w.name("which").value(name)
            w.name("image").value(Base64.encodeToString(bytes, Base64.NO_WRAP))
            w.endObject()
            n++
        }
        return n
    }

    private fun writeSms(ctx: Context, w: JsonWriter): Int {
        val cols = arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE,
            Telephony.Sms.READ, Telephony.Sms.STATUS, Telephony.Sms.SERVICE_CENTER, Telephony.Sms.SUBJECT,
        )
        var n = 0
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, cols, null, null, "${Telephony.Sms.DATE} ASC")?.use { c ->
            while (c.moveToNext()) {
                w.beginObject()
                w.name("address").value(c.getString(0).orEmpty())
                w.name("body").value(c.getString(1).orEmpty())
                w.name("date").value(c.getLong(2))
                w.name("date_sent").value(c.getLong(3))
                w.name("type").value(c.getInt(4).toLong())
                w.name("read").value(c.getInt(5).toLong())
                w.name("status").value(c.getInt(6).toLong())
                w.name("service_center").value(c.getString(7).orEmpty())
                w.name("subject").value(c.getString(8).orEmpty())
                w.endObject()
                n++
            }
        }
        return n
    }

    private const val PHOTO = "vnd.android.cursor.item/photo"

    /** Every data row of every contact (names, phones, emails, addresses, notes, photo…), grouped per contact. */
    private fun writeContacts(ctx: Context, w: JsonWriter): Int {
        val data = Array(14) { "data${it + 1}" }
        val cols = arrayOf(ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE) + data + arrayOf("data15")
        var n = 0
        var current = -1L
        var open = false
        ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI, cols, null, null, "${ContactsContract.Data.RAW_CONTACT_ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                val raw = c.getLong(0)
                if (raw != current) {
                    if (open) { w.endArray(); w.endObject() }
                    w.beginObject()
                    w.name("rows")
                    w.beginArray()
                    open = true
                    current = raw
                    n++
                }
                val mime = c.getString(1) ?: continue
                w.beginObject()
                w.name("m").value(mime)
                w.name("d")
                w.beginObject()
                for (i in 0 until 14) {
                    val v = c.getString(2 + i)
                    if (!v.isNullOrEmpty()) w.name((i + 1).toString()).value(v)
                }
                w.endObject()
                if (mime == PHOTO) {
                    val blob = c.getBlob(16)
                    if (blob != null && blob.size <= 400_000) w.name("p").value(Base64.encodeToString(blob, Base64.NO_WRAP))
                }
                w.endObject()
            }
        }
        if (open) { w.endArray(); w.endObject() }
        return n
    }

    // ------------------------------------------------------------------ restore

    /** Adds what the file holds and this phone does not have yet. Nothing is deleted or changed. */
    fun import(ctx: Context, kind: PhoneKind, file: File): ImportResult = when (kind) {
        PhoneKind.CONTACTS -> importContacts(ctx, file)
        PhoneKind.CALLS -> importCalls(ctx, file)
        PhoneKind.SMS -> importSms(ctx, file)
        PhoneKind.WALLPAPER -> importWallpapers(ctx, file)
    }

    /** Calls the reader once per entry object of the file's "items" array. */
    private inline fun forEachItem(file: File, onItem: (JsonReader) -> Unit) {
        JsonReader(file.bufferedReader(Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "items" && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray()
                    while (r.hasNext()) onItem(r)
                    r.endArray()
                } else {
                    r.skipValue()
                }
            }
            r.endObject()
        }
    }

    private fun importCalls(ctx: Context, file: File): ImportResult {
        val cr = ctx.contentResolver
        val have = HashSet<String>()
        cr.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) have += callKey(c.getString(0), c.getLong(1), c.getInt(2))
        }
        val batch = ArrayList<ContentValues>()
        var added = 0
        var skipped = 0
        fun flush() {
            if (batch.isNotEmpty()) { cr.bulkInsert(CallLog.Calls.CONTENT_URI, batch.toTypedArray()); batch.clear() }
        }
        forEachItem(file) { r ->
            var number = ""; var date = 0L; var duration = 0L; var type = 0; var name = ""
            var ntype = 0; var nlabel = ""; var iso = ""; var pres = 1
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "number" -> number = r.nextString()
                    "date" -> date = r.nextLong()
                    "duration" -> duration = r.nextLong()
                    "type" -> type = r.nextInt()
                    "name" -> name = r.nextString()
                    "numbertype" -> ntype = r.nextInt()
                    "numberlabel" -> nlabel = r.nextString()
                    "countryiso" -> iso = r.nextString()
                    "presentation" -> pres = r.nextInt()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (!have.add(callKey(number, date, type))) { skipped++; return@forEachItem }
            batch += ContentValues().apply {
                put(CallLog.Calls.NUMBER, number); put(CallLog.Calls.DATE, date); put(CallLog.Calls.DURATION, duration)
                put(CallLog.Calls.TYPE, type); put(CallLog.Calls.NEW, 0)
                put(CallLog.Calls.NUMBER_PRESENTATION, pres)
                if (name.isNotEmpty()) put(CallLog.Calls.CACHED_NAME, name)
                put(CallLog.Calls.CACHED_NUMBER_TYPE, ntype)
                if (nlabel.isNotEmpty()) put(CallLog.Calls.CACHED_NUMBER_LABEL, nlabel)
                if (iso.isNotEmpty()) put(CallLog.Calls.COUNTRY_ISO, iso)
            }
            added++
            if (batch.size >= 500) flush()
        }
        flush()
        return ImportResult(added, skipped)
    }

    /** Sets the saved wallpaper(s). Unlike the other kinds this replaces what is there, and the user can simply change it back. */
    private fun importWallpapers(ctx: Context, file: File): ImportResult {
        val wm = WallpaperManager.getInstance(ctx)
        var added = 0
        var skipped = 0
        forEachItem(file) { r ->
            var which = ""; var image = ""
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "which" -> which = r.nextString()
                    "image" -> image = r.nextString()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (image.isEmpty()) { skipped++; return@forEachItem }
            val flag = if (which == "lock") WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
            wm.setStream(ByteArrayInputStream(Base64.decode(image, Base64.NO_WRAP)), null, true, flag)
            added++
        }
        return ImportResult(added, skipped)
    }

    private fun importSms(ctx: Context, file: File): ImportResult {
        val cr = ctx.contentResolver
        val have = HashSet<String>()
        cr.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.BODY), null, null, null)?.use { c ->
            while (c.moveToNext()) have += smsKey(c.getString(0), c.getLong(1), c.getInt(2), c.getString(3))
        }
        var added = 0
        var skipped = 0
        forEachItem(file) { r ->
            var address = ""; var body = ""; var date = 0L; var sent = 0L; var type = 1
            var read = 1; var status = -1; var center = ""; var subject = ""
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "address" -> address = r.nextString()
                    "body" -> body = r.nextString()
                    "date" -> date = r.nextLong()
                    "date_sent" -> sent = r.nextLong()
                    "type" -> type = r.nextInt()
                    "read" -> read = r.nextInt()
                    "status" -> status = r.nextInt()
                    "service_center" -> center = r.nextString()
                    "subject" -> subject = r.nextString()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (!have.add(smsKey(address, date, type, body))) { skipped++; return@forEachItem }
            val v = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body); put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.DATE_SENT, sent); put(Telephony.Sms.TYPE, type); put(Telephony.Sms.READ, read)
                put(Telephony.Sms.SEEN, 1); put(Telephony.Sms.STATUS, status)
                if (center.isNotEmpty()) put(Telephony.Sms.SERVICE_CENTER, center)
                if (subject.isNotEmpty()) put(Telephony.Sms.SUBJECT, subject)
            }
            if (cr.insert(Telephony.Sms.CONTENT_URI, v) != null) added++ else skipped++
        }
        return ImportResult(added, skipped)
    }

    private class Row(val mime: String, val data: Map<Int, String>, val photo: String?)
    private class Person(val name: String, val phones: List<String>, val rows: List<Row>)

    private fun readPeople(file: File): List<Person> {
        val out = ArrayList<Person>()
        forEachItem(file) { r ->
            val rows = ArrayList<Row>()
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "rows") { r.skipValue(); continue }
                r.beginArray()
                while (r.hasNext()) {
                    var mime = ""; var photo: String? = null; val data = HashMap<Int, String>()
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "m" -> mime = r.nextString()
                            "p" -> photo = r.nextString()
                            "d" -> {
                                r.beginObject()
                                while (r.hasNext()) {
                                    val k = r.nextName().toIntOrNull()
                                    val v = r.nextString()
                                    if (k != null) data[k] = v
                                }
                                r.endObject()
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (mime.isNotEmpty()) rows += Row(mime, data, photo)
                }
                r.endArray()
            }
            r.endObject()
            val name = rows.firstOrNull { it.mime == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }?.data?.get(1).orEmpty()
            val phones = rows.filter { it.mime == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE }.mapNotNull { it.data[1] }
            out += Person(name, phones, rows)
        }
        return out
    }

    private fun importContacts(ctx: Context, file: File): ImportResult {
        val cr = ctx.contentResolver
        // What is already here, as "name|phones" keys.
        val names = HashMap<Long, String>()
        val phones = HashMap<Long, MutableList<String>>()
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val raw = c.getLong(0)
                when (c.getString(1)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> names[raw] = c.getString(2).orEmpty()
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> phones.getOrPut(raw) { ArrayList() }.add(c.getString(2).orEmpty())
                }
            }
        }
        val have = HashSet<String>()
        for (raw in names.keys + phones.keys) have += contactKey(names[raw], phones[raw].orEmpty())

        val ops = ArrayList<ContentProviderOperation>()
        fun flush() {
            if (ops.isNotEmpty()) { cr.applyBatch(ContactsContract.AUTHORITY, ops); ops.clear() }
        }
        var added = 0
        var skipped = 0
        for (p in readPeople(file)) {
            if (p.rows.isEmpty() || !have.add(contactKey(p.name, p.phones))) { skipped++; continue }
            if (ops.size + 1 + p.rows.size > 400) flush()
            val back = ops.size
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
            for (row in p.rows) {
                val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                    .withValue(ContactsContract.Data.MIMETYPE, row.mime)
                for ((i, v) in row.data) b.withValue("data$i", v)
                row.photo?.let { b.withValue("data15", Base64.decode(it, Base64.NO_WRAP)) }
                ops += b.build()
            }
            added++
        }
        flush()
        return ImportResult(added, skipped)
    }
}
