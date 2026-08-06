package juricabi.com.telemetry.protocol

/**
 * Whether a competing telemetry source is still actually streaming.
 *
 * The same shape as [MavPositionSource], for the same reason: a preferred
 * source is a stream, not a capability discovered once. The preferred side
 * calls [arrived] on each of its frames; the fallback side asks [fresh]
 * before publishing, and each such ask counts as an alternate frame so a
 * replay — decoded in a burst where wall time barely moves — still hands
 * over after enough silence.
 */
internal class SourceFreshness(
    private val nanoTime: () -> Long = System::nanoTime
) {
    private var at = Long.MIN_VALUE
    private var alternateFrames = 0

    fun arrived() {
        at = nanoTime()
        alternateFrames = 0
    }

    fun fresh(): Boolean {
        if (at == Long.MIN_VALUE) return false
        alternateFrames++
        return alternateFrames <= MAX_ALTERNATE_FRAMES &&
            nanoTime() - at <= TIMEOUT_NANOS
    }

    private companion object {
        const val TIMEOUT_NANOS = 3_000_000_000L
        const val MAX_ALTERNATE_FRAMES = 60
    }
}
