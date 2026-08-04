package juricabi.com.telemetry.api

import android.util.Log
import juricabi.com.telemetry.proto.fr24.*
import com.google.protobuf.FieldMask
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

class Fr24Client {

    companion object {
        private const val TAG = "FR24"
        private const val ENDPOINT =
            "https://data-feed.flightradar24.com/fr24.feed.api.v1.Feed/LiveFeed"
    }

    fun fetchLiveFeed(bounds: LocationBoundaries, altitudeCeilingMeters: Int): List<Flight> {
        val ceilingFt = (altitudeCeilingMeters / 0.3048).toInt()

        val request = LiveFeedRequest.newBuilder()
            .setBounds(bounds)
            .setLimit(1500)
            .setMaxage(14400)
            .setFieldMask(
                FieldMask.newBuilder()
                    .addPaths("flight")
                    .addPaths("reg")
                    .addPaths("route")
                    .addPaths("type")
                    .build()
            )
            .build()

        val serialized = request.toByteArray()
        val body = encodeMessage(serialized)
        Log.d(TAG, "Fetching LiveFeed: bounds=[S=${bounds.south},N=${bounds.north},W=${bounds.west},E=${bounds.east}], ceiling=${altitudeCeilingMeters}m (${ceilingFt}ft)")
        return executeRequest(body)
    }

    private fun encodeMessage(serialized: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 4 + serialized.size)
        buffer.put(0x00.toByte()) // no compression
        buffer.putInt(serialized.size)
        buffer.put(serialized)
        return buffer.array()
    }

    private fun executeRequest(body: ByteArray): List<Flight> {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/grpc-web+proto")
            connection.setRequestProperty("X-User-Agent", "grpc-web-javascript/0.1")
            connection.setRequestProperty("X-Grpc-Web", "1")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty(
                "fr24-device-id",
                "web-000000000-000000000000000000000"
            )
            connection.setRequestProperty("fr24-platform", "web-25.061.0929")
            connection.setRequestProperty("x-envoy-retry-grpc-on", "unavailable")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            connection.outputStream.use { it.write(body) }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.e(TAG, "HTTP error $responseCode: ${connection.responseMessage}")
                return emptyList()
            }

            val responseBytes = connection.inputStream.use { input ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    baos.write(buf, 0, n)
                }
                baos.toByteArray()
            }

            val flights = decodeResponse(responseBytes)
            Log.d(TAG, "Decoded ${flights.size} flights from response")
            return flights
        } catch (e: Exception) {
            Log.e(TAG, "FR24 fetch failed", e)
            return emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeResponse(data: ByteArray): List<Flight> {
        if (data.size < 5) {
            Log.e(TAG, "Response too short: ${data.size} bytes")
            return emptyList()
        }

        // gRPC-web frame: 1 byte flags + 4 byte length + payload
        // There may be multiple frames; we parse the first DATA frame (flag byte 0x00)
        var offset = 0
        while (offset + 5 <= data.size) {
            val flag = data[offset].toInt() and 0xFF
            val length = ByteBuffer.wrap(data, offset + 1, 4).int
            offset += 5

            if (flag == 0x00 && length > 0 && offset + length <= data.size) {
                // DATA frame
                val response = LiveFeedResponse.parseFrom(
                    data.copyOfRange(offset, offset + length)
                )
                return response.flightsListList
            }

            offset += length
        }

        return emptyList()
    }
}
