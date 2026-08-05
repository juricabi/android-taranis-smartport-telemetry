package juricabi.com.telemetry.protocol

/**
 * Whether MAVLink's estimator position is still the source to trust.
 *
 * GLOBAL_POSITION_INT is preferable to GPS_RAW_INT, but it is a stream, not a
 * capability discovered once. Flight controllers may restart a component or
 * change their requested message set while the link stays up. A permanent
 * Boolean then suppresses every valid raw fix that follows and freezes both
 * maps at the last estimator position.
 *
 * Wall time handles a live link. Replays are decoded in a burst, so alternate
 * frames provide the second bound there; an estimator stream that is still
 * arriving resets both on every frame.
 */
internal class MavPositionSource(
    private val nanoTime: () -> Long = System::nanoTime
) {
    private var globalAt = Long.MIN_VALUE
    private var alternateFrames = 0

    fun globalArrived() {
        globalAt = nanoTime()
        alternateFrames = 0
    }

    /** Called for each raw-GPS or VFR-HUD frame that can stand in for global position. */
    fun preferGlobal(): Boolean {
        if (globalAt == Long.MIN_VALUE) return false
        alternateFrames++
        return alternateFrames <= MAX_ALTERNATE_FRAMES &&
            nanoTime() - globalAt <= GLOBAL_TIMEOUT_NANOS
    }

    private companion object {
        const val GLOBAL_TIMEOUT_NANOS = 3_000_000_000L
        const val MAX_ALTERNATE_FRAMES = 60
    }
}
