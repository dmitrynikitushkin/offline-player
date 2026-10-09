package app.offlineplayer.ui

import java.util.Locale

fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return "—"
    val units = listOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var u = 0
    while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
    return if (u <= 1) "%.0f %s".format(Locale.US, v, units[u]) else "%.1f %s".format(Locale.US, v, units[u])
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatEta(sec: Long?): String = if (sec == null || sec <= 0) "" else " · ${formatDuration(sec * 1000)} left"
