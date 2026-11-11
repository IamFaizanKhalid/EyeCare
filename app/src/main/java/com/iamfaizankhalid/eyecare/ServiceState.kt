package com.iamfaizankhalid.eyecare

/**
 * Small in-process bridge between [BreakService] and [MainActivity].
 *
 * The service is the single source of truth; the activity only reads these values while it is
 * visible. Keeping the UI in sync this way avoids a binder and works even if the activity is
 * recreated.
 */
object ServiceState {
    @Volatile
    var running: Boolean = false

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var breakActive: Boolean = false

    @Volatile
    var remainingMs: Long = BreakService.SESSION_MS

    @Volatile
    var breakRemainingMs: Long = 0L
}
