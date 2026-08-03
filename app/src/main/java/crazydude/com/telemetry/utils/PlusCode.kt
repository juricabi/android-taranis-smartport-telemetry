package crazydude.com.telemetry.utils

/**
 * Open Location Code, the "plus code" that Google Maps accepts in its search
 * box.
 *
 * Eleven characters, naming a cell about 2.8m by 2.4m — finer than the fix
 * itself, so nothing the GPS knows is thrown away. Ten characters, the more
 * common length, would round that to roughly 14m by 10m.
 *
 * Worked out in whole units of the smallest cell rather than by subtracting
 * degrees as it goes: the remainders in that approach land a hair below a cell
 * edge often enough to name the neighbouring cell.
 */
object PlusCode {

    private const val ALPHABET = "23456789CFGHJMPQRVWX"

    /** A ten character code names a cell of 1/8000 of a degree. */
    private const val PAIR_PRECISION = 8000L

    /** Each further character splits the cell into a grid taller than it is wide. */
    private const val GRID_ROWS = 5L
    private const val GRID_COLUMNS = 4L
    private const val GRID_STEPS = 5

    // The standard's full depth. Only the first grid character is kept, but
    // working at this precision and discarding the rest is what the reference
    // implementation does, and shorter arithmetic disagrees with it on cell
    // boundaries.
    private const val LAT_PRECISION = PAIR_PRECISION * 3125L   // 5^5
    private const val LON_PRECISION = PAIR_PRECISION * 1024L   // 4^5

    fun encode(latitude: Double, longitude: Double): String {
        var lat = latitude
        if (lat < -90.0) lat = -90.0
        if (lat > 90.0) lat = 90.0

        var lon = longitude
        while (lon < -180.0) lon += 360.0
        while (lon >= 180.0) lon -= 360.0

        // rounded before truncating, so a value a fraction below a boundary is
        // not carried down into the wrong cell
        var latVal = Math.round((lat + 90.0) * LAT_PRECISION * 1e6) / 1000000L
        var lonVal = Math.round((lon + 180.0) * LON_PRECISION * 1e6) / 1000000L

        // exactly 90 belongs to the last cell, not to the one past the pole
        if (latVal >= 180L * LAT_PRECISION) latVal = 180L * LAT_PRECISION - 1

        // The grid characters come out least significant first; the last one
        // computed is the one that follows the ten pair characters.
        var grid = ALPHABET[0]
        for (i in 0 until GRID_STEPS) {
            grid = ALPHABET[((latVal % GRID_ROWS) * GRID_COLUMNS + (lonVal % GRID_COLUMNS)).toInt()]
            latVal /= GRID_ROWS
            lonVal /= GRID_COLUMNS
        }

        val digits = CharArray(11)
        digits[10] = grid
        for (i in 4 downTo 0) {
            digits[i * 2] = ALPHABET[(latVal % 20L).toInt()]
            digits[i * 2 + 1] = ALPHABET[(lonVal % 20L).toInt()]
            latVal /= 20L
            lonVal /= 20L
        }

        // the separator always sits after eight characters
        return String(digits, 0, 8) + "+" + String(digits, 8, 3)
    }
}
