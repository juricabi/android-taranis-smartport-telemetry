package juricabi.com.telemetry.gl

/**
 * The freshest reported height to attach to each position fix.
 *
 * GPS height is preferred because it is normally above sea level. A link may
 * also carry a barometric or above-home height, which is kept ready as the
 * fallback rather than discarded while GPS is healthy. Both expire: by
 * monotonic time on a live link, and by fixes on a replay decoded too quickly
 * for wall time to describe the recording.
 */
internal class FlightAltitude(
    private val nanoTime: () -> Long = System::nanoTime
) {
    private class Sample {
        var value = Float.NaN
        var at = Long.MIN_VALUE
        var fixes = STALE_AFTER_FIXES + 1
    }

    private val gps = Sample()
    private val fallback = Sample()

    fun onGps(value: Float) = remember(gps, value)

    fun onFallback(value: Float) = remember(fallback, value)

    /** The height for the end of the next [fixCount] fixes, or NaN if none is fresh. */
    fun forFix(fixCount: Int = 1): Float {
        age(gps, fixCount)
        age(fallback, fixCount)
        val now = nanoTime()
        return when {
            isFresh(gps, now) -> gps.value
            isFresh(fallback, now) -> fallback.value
            else -> Float.NaN
        }
    }

    fun clear() {
        clear(gps)
        clear(fallback)
    }

    private fun remember(sample: Sample, value: Float) {
        sample.value = value
        sample.at = nanoTime()
        sample.fixes = 0
    }

    private fun age(sample: Sample, count: Int) {
        if (count <= 0 || sample.fixes > STALE_AFTER_FIXES) return
        sample.fixes = Math.min(
            (STALE_AFTER_FIXES + 1).toLong(), sample.fixes.toLong() + count
        ).toInt()
    }

    private fun isFresh(sample: Sample, now: Long): Boolean =
        !sample.value.isNaN() && sample.fixes <= STALE_AFTER_FIXES &&
            now - sample.at <= STALE_AFTER_NANOS

    private fun clear(sample: Sample) {
        sample.value = Float.NaN
        sample.at = Long.MIN_VALUE
        sample.fixes = STALE_AFTER_FIXES + 1
    }

    private companion object {
        const val STALE_AFTER_NANOS = 10_000_000_000L
        const val STALE_AFTER_FIXES = 100
    }
}
