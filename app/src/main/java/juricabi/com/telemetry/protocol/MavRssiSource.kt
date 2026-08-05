package juricabi.com.telemetry.protocol

/** Prefer RADIO_STATUS RSSI only while that stream is still arriving. */
internal class MavRssiSource(
    private val nanoTime: () -> Long = System::nanoTime
) {
    private var radioStatusAt = Long.MIN_VALUE
    private var fallbackFrames = 0

    fun radioStatusArrived() {
        radioStatusAt = nanoTime()
        fallbackFrames = 0
    }

    /** Called for each RC frame whose RSSI can replace RADIO_STATUS. */
    fun preferRadioStatus(): Boolean {
        if (radioStatusAt == Long.MIN_VALUE) return false
        fallbackFrames++
        return fallbackFrames <= MAX_FALLBACK_FRAMES &&
            nanoTime() - radioStatusAt <= RADIO_STATUS_TIMEOUT_NANOS
    }

    private companion object {
        const val RADIO_STATUS_TIMEOUT_NANOS = 3_000_000_000L
        const val MAX_FALLBACK_FRAMES = 60
    }
}
