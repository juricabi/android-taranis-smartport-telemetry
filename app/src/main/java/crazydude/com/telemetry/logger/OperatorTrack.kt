package crazydude.com.telemetry.logger

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Where the person holding the phone was, read back out of the CSV recorded
 * beside a flight.
 *
 * The recording is of what came off the link, so it says everything about the
 * model and nothing about the operator — and half of what a flight looked like
 * is the operator: the line home is drawn to them, the arrow and its ring are
 * drawn on them, and the distance home is measured from them. Replayed against
 * wherever the phone happens to be now, all three are wrong, and a flight
 * replayed at home puts the line home across the county.
 *
 * The CSV carries a row five times a second with the time on it, so it is also
 * the only honest record of when the flight was: [startedAt] and [endedAt] are
 * its first and last rows.
 */
class OperatorTrack private constructor(
    private val times: LongArray,
    private val marks: LongArray,
    private val lats: DoubleArray,
    private val lons: DoubleArray,
    private val accuracies: FloatArray,
    private val headings: FloatArray
) {

    val startedAt: Long get() = times[0]
    val endedAt: Long get() = times[times.size - 1]

    class Where(
        val lat: Double,
        val lon: Double,
        val accuracy: Float,
        val heading: Float
    )

    /**
     * Where they were at that moment, between the two rows either side of it.
     *
     * Between rows rather than at the nearer one: a replay runs many times
     * faster than the flight did, so rows arrive in bursts, and stepping from
     * one to the next made the arrow jump where the live one glides.
     */
    fun at(time: Long): Where {
        val last = times.size - 1
        if (time <= times[0]) return row(0)
        if (time >= times[last]) return row(last)

        var low = 0
        var high = last
        while (low + 1 < high) {
            val middle = (low + high) / 2
            if (times[middle] <= time) low = middle else high = middle
        }
        val span = times[high] - times[low]
        val part = if (span <= 0L) 0f else (time - times[low]).toFloat() / span

        return Where(
            lats[low] + (lats[high] - lats[low]) * part,
            lons[low] + (lons[high] - lons[low]) * part,
            between(accuracies[low], accuracies[high], part),
            turn(headings[low], headings[high], part)
        )
    }

    private fun row(i: Int) = Where(lats[i], lons[i], accuracies[i], headings[i])

    /**
     * The time at which the recording had reached that many bytes.
     *
     * This is what makes a replay tell the truth about a link that went quiet:
     * rows go on being written every fifth of a second through the silence, all
     * of them marked with the same place in the recording — so a replay sitting
     * at that place is somewhere in that stretch of rows, and the clock stands
     * still there exactly as it did on the day.
     *
     * The stretch is answered at its end, so the clock reads the moment the
     * link came back rather than the moment it went away: the packet being
     * drawn is the first one after the silence, and that is when it arrived.
     *
     * NaN of a kind: no marks in this CSV at all, and there is nothing to
     * answer with.
     */
    fun timeAtMark(mark: Long): Long? {
        if (marks.isEmpty() || marks[marks.size - 1] < 0L) return null
        if (mark <= marks[0]) return times[0]
        val last = marks.size - 1
        if (mark >= marks[last]) return times[last]

        var low = 0
        var high = last
        while (low + 1 < high) {
            val middle = (low + high) / 2
            if (marks[middle] <= mark) low = middle else high = middle
        }
        // low is the last row that had got no further than here, and high is
        // the first that had got past it
        return times[low]
    }

    private fun between(from: Float, to: Float, part: Float): Float {
        if (from.isNaN()) return to
        if (to.isNaN()) return from
        return from + (to - from) * part
    }

    /** The short way round, so north to north-west does not go the long way. */
    private fun turn(from: Float, to: Float, part: Float): Float {
        if (from.isNaN()) return to
        if (to.isNaN()) return from
        val by = ((to - from) % 360f + 540f) % 360f - 180f
        return ((from + by * part) % 360f + 360f) % 360f
    }

    companion object {

        private const val DATE = "Date"
        private const val TIME = "Time"
        private const val LAT = "MyLat"
        private const val LON = "MyLon"
        private const val ACCURACY = "MyAcc(m)"
        private const val HEADING = "MyHdg(deg)"
        private const val MARK = "LogBytes"

        /**
         * Null where there is nothing to read: no CSV beside the log, or one
         * recorded before any of this, or one whose rows carry no position
         * because it was recorded with that turned off. Nothing is drawn then,
         * which is the point — a guess about where somebody stood is worse than
         * saying nothing.
         */
        fun read(csv: File): OperatorTrack? {
            if (!csv.exists()) return null
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

            var date = -1
            var time = -1
            var lat = -1
            var lon = -1
            var accuracy = -1
            var heading = -1
            var mark = -1

            val times = ArrayList<Long>()
            val marks = ArrayList<Long>()
            val lats = ArrayList<Double>()
            val lons = ArrayList<Double>()
            val accuracies = ArrayList<Float>()
            val headings = ArrayList<Float>()

            // A CSV recorded before any of this has a Date and a Time and no
            // operator at all. Reading on would ask for column minus one.
            var usable = true

            csv.forEachLine { line ->
                if (!usable) return@forEachLine
                val cell = line.split(",")
                if (date < 0) {
                    // the header, whose order is not worth relying on
                    for (i in cell.indices) {
                        when (cell[i].trim().trim('"')) {
                            DATE -> date = i
                            TIME -> time = i
                            LAT -> lat = i
                            LON -> lon = i
                            ACCURACY -> accuracy = i
                            HEADING -> heading = i
                            MARK -> mark = i
                        }
                    }
                    if (time < 0 || lat < 0 || lon < 0) usable = false
                    return@forEachLine
                } else if (cell.size > Math.max(Math.max(date, time), Math.max(lat, lon))) {
                    val whenAt = try {
                        stamp.parse(cell[date].trim() + " " + cell[time].trim())?.time
                    } catch (e: Exception) {
                        null
                    }
                    val north = cell[lat].trim().toDoubleOrNull()
                    val east = cell[lon].trim().toDoubleOrNull()
                    if (whenAt != null && north != null && east != null) {
                        times.add(whenAt)
                        marks.add(cellLong(cell, mark))
                        lats.add(north)
                        lons.add(east)
                        accuracies.add(cellFloat(cell, accuracy))
                        headings.add(cellFloat(cell, heading))
                    }
                }
            }

            // one row is a place but not a flight: there is nothing to run
            // between, and the clock would have nowhere to go
            if (times.size < 2) return null
            return OperatorTrack(
                LongArray(times.size) { times[it] },
                LongArray(marks.size) { marks[it] },
                DoubleArray(lats.size) { lats[it] },
                DoubleArray(lons.size) { lons[it] },
                FloatArray(accuracies.size) { accuracies[it] },
                FloatArray(headings.size) { headings[it] }
            )
        }

        private fun cellLong(cell: List<String>, at: Int): Long {
            if (at < 0 || at >= cell.size) return -1L
            return cell[at].trim().toLongOrNull() ?: -1L
        }

        private fun cellFloat(cell: List<String>, at: Int): Float {
            if (at < 0 || at >= cell.size) return Float.NaN
            return cell[at].trim().toFloatOrNull() ?: Float.NaN
        }
    }
}
