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
    private val stood: LongArray,
    private val lats: DoubleArray,
    private val lons: DoubleArray,
    private val accuracies: FloatArray,
    private val headings: FloatArray
) {

    val startedAt: Long get() = times[0]
    val endedAt: Long get() = times[times.size - 1]

    /** Whether anything is known about where the operator stood. */
    fun hasPlaces(): Boolean = stood.isNotEmpty()

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
    fun at(time: Long): Where? {
        if (stood.isEmpty()) return null
        val last = stood.size - 1
        if (time <= stood[0]) return row(0)
        if (time >= stood[last]) return row(last)

        var low = 0
        var high = last
        while (low + 1 < high) {
            val middle = (low + high) / 2
            if (stood[middle] <= time) low = middle else high = middle
        }
        val span = stood[high] - stood[low]
        val part = if (span <= 0L) 0f else (time - stood[low]).toFloat() / span

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
     * The stretch is answered at its beginning: the first row that had seen
     * this much of the recording. The packet being drawn is the last one before
     * the silence, so the honest time for it is the moment the link went quiet,
     * and the packets after it read the moment it came back — the jump lands
     * between them, which is where it happened.
     *
     * Null where this CSV has no marks in it at all, and there is nothing to
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
            if (marks[middle] < mark) low = middle else high = middle
        }
        // high is the first row that had seen this much written
        return times[high]
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
        return crazydude.com.telemetry.utils.GeoUtils.turnTowards(from, to, part)
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
         * Null only where there is no time to read: no CSV beside the log, or
         * one recorded before any of this.
         *
         * A CSV with times and no positions — recording turned off, or a flight
         * watched with the app in somebody's pocket — still gives a clock. The
         * operator is drawn from the rows that have a place and nothing is
         * drawn from the rows that have none, which is why the two are kept
         * apart.
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
            val stood = ArrayList<Long>()
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
                    // A time is enough to be worth reading. The place is not
                    // required: the screen stops saying where the phone is while
                    // it is in the background, and the link goes on recording —
                    // so most of a long flight can be rows with a time, a mark
                    // and no position, and those rows are the clock.
                    if (time < 0 || date < 0) usable = false
                    return@forEachLine
                } else if (cell.size > Math.max(date, time)) {
                    val whenAt = try {
                        stamp.parse(cell[date].trim() + " " + cell[time].trim())?.time
                    } catch (e: Exception) {
                        null
                    }
                    if (whenAt != null) {
                        times.add(whenAt)
                        marks.add(cellLong(cell, mark))
                        val north = cellDouble(cell, lat)
                        val east = cellDouble(cell, lon)
                        if (north != null && east != null) {
                            stood.add(whenAt)
                            lats.add(north)
                            lons.add(east)
                            accuracies.add(cellFloat(cell, accuracy))
                            headings.add(cellFloat(cell, heading))
                        }
                    }
                }
            }

            // A row cut off half-written — the app killed, the battery gone,
            // which is how recordings usually end — has a place and a time but
            // no mark. Judging the whole file by its last row threw away the
            // mapping and quietly went back to spreading the flight evenly.
            while (marks.size > 1 && marks[marks.size - 1] < 0L) {
                val dropped = times.removeAt(times.size - 1)
                marks.removeAt(marks.size - 1)
                if (stood.isNotEmpty() && stood[stood.size - 1] == dropped) {
                    stood.removeAt(stood.size - 1)
                    lats.removeAt(lats.size - 1)
                    lons.removeAt(lons.size - 1)
                    accuracies.removeAt(accuracies.size - 1)
                    headings.removeAt(headings.size - 1)
                }
            }

            // one row is a place but not a flight: there is nothing to run
            // between, and the clock would have nowhere to go
            if (times.size < 2) return null
            return OperatorTrack(
                LongArray(times.size) { times[it] },
                LongArray(marks.size) { marks[it] },
                LongArray(stood.size) { stood[it] },
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

        private fun cellDouble(cell: List<String>, at: Int): Double? {
            if (at < 0 || at >= cell.size) return null
            return cell[at].trim().toDoubleOrNull()
        }

        private fun cellFloat(cell: List<String>, at: Int): Float {
            if (at < 0 || at >= cell.size) return Float.NaN
            return cell[at].trim().toFloatOrNull() ?: Float.NaN
        }
    }
}
