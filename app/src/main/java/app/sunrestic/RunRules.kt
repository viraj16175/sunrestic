package app.sunrestic

/**
 * When a scheduled run may be skipped because a backup was "just done". The stored time can be wrong, for example
 * after the phone's clock was changed or when a snapshot carries a time ahead of this phone. A time in the future
 * never counts as "just done".
 */
object RunRules {
    /** Whole minutes since [last], or null when [last] is unknown (0) or lies in the future. */
    fun minutesSince(last: Long, now: Long): Long? = if (last <= 0L || last > now) null else (now - last) / 60_000L

    /** A retry (after waiting for charging or Wi-Fi) is pointless if a backup was saved within the last hour. */
    fun skipRetry(last: Long, now: Long): Boolean = (minutesSince(last, now) ?: Long.MAX_VALUE) < 60L

    /** The tap-to-start reminder stays quiet if a backup was saved within the last 12 hours. */
    fun skipReminder(last: Long, now: Long): Boolean = (minutesSince(last, now) ?: Long.MAX_VALUE) < 12 * 60L
}
