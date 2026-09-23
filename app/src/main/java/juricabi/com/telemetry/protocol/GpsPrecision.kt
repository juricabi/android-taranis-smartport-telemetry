package juricabi.com.telemetry.protocol

/**
 * How well the model knows where it is, in whichever form its link says it.
 *
 * Not one quantity. An accuracy is a distance, estimated by the receiver; a
 * dilution of precision is a unitless factor of the satellites' geometry;
 * S.Port has room for one decimal digit of the latter. None converts into
 * another — a DOP becomes metres only through the receiver's own error
 * budget, which nothing sends — so each is shown as what it is.
 */
sealed class GpsPrecision {
    abstract fun text(): String

    data class Metres(val metres: Float) : GpsPrecision() {
        override fun text() = "±${oneDecimal(metres)} m"
    }

    data class Hdop(val hdop: Float) : GpsPrecision() {
        override fun text() = "HDOP ${oneDecimal(hdop)}"
    }

    /**
     * iNav's and Betaflight's S.Port digit, 9 the best. The two draw their
     * steps half a unit apart — iNav's 9 is 1.0 or better, Betaflight's under
     * 1.5 — and the sensor id does not say which sent it, so the bound shown
     * is the one true of both.
     */
    data class HdopDigit(val digit: Int) : GpsPrecision() {
        override fun text() =
            if (digit <= 0) "HDOP >5" else "HDOP ≤${oneDecimal(1.5f + 0.5f * (9 - digit.coerceAtMost(9)))}"
    }

    protected fun oneDecimal(value: Float) = "%.1f".format(value)
}
