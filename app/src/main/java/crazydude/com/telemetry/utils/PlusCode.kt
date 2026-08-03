package crazydude.com.telemetry.utils

/**
 * Open Location Code, the "plus code" that Google Maps accepts in its search
 * box. Ten digits are accurate to roughly 14 metres, which is close enough to
 * walk to and short enough to read out or send in a message.
 */
object PlusCode {

    private const val ALPHABET = "23456789CFGHJMPQRVWX"

    // degrees covered by each pair of digits
    private val RESOLUTIONS = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)

    fun encode(latitude: Double, longitude: Double): String {
        var lat = latitude
        if (lat < -90.0) lat = -90.0
        if (lat > 89.9999999) lat = 89.9999999

        var lon = longitude
        while (lon < -180.0) lon += 360.0
        while (lon >= 180.0) lon -= 360.0

        var adjustedLat = lat + 90.0
        var adjustedLon = lon + 180.0

        val code = StringBuilder()
        for (resolution in RESOLUTIONS) {
            var latDigit = Math.floor(adjustedLat / resolution).toInt()
            var lonDigit = Math.floor(adjustedLon / resolution).toInt()
            if (latDigit > 19) latDigit = 19
            if (lonDigit > 19) lonDigit = 19

            code.append(ALPHABET[latDigit])
            code.append(ALPHABET[lonDigit])

            adjustedLat -= latDigit * resolution
            adjustedLon -= lonDigit * resolution

            if (code.length == 8) code.append('+')
        }
        return code.toString()
    }
}
