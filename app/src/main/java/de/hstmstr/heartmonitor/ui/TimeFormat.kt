package de.hstmstr.heartmonitor.ui

import java.util.Locale

/**
 * Elapsed seconds as a stopwatch string: `m:ss`, or `h:mm:ss` once past an
 * hour. Seconds are truncated (like a running clock), never rounded up.
 */
internal fun formatElapsed(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}
