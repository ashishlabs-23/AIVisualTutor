package context

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BlenderTcpStateProvider(
    private val port: Int = DEFAULT_PORT,
    private val host: InetAddress = InetAddress.getByName(LOOPBACK_ADDRESS),
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS
) : BlenderStateProvider {
    init {
        require(port in 1..65535) { "Blender state port must be in 1..65535." }
        require(host.isLoopbackAddress) { "Blender state provider must connect to a loopback address." }
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Blender state timeout must be in 1..$MAX_TIMEOUT_MILLIS ms." }
    }

    override suspend fun observe(): BlenderStateObservation = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                socket.soTimeout = timeoutMillis
                val input = ResponseReader(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write(REQUEST)
                output.flush()
                readObservation(input)
            }
        } catch (timeout: SocketTimeoutException) {
            unavailable(BlenderObservationStatus.FAILED, "Blender state request timed out.")
        } catch (missing: ConnectException) {
            unavailable(BlenderObservationStatus.UNAVAILABLE, "No Blender state adapter is listening on loopback port $port.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            unavailable(
                BlenderObservationStatus.FAILED,
                "Blender state request failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun readObservation(input: ResponseReader): BlenderStateObservation {
        val header = input.readUtf8Line()
            ?: return unavailable(BlenderObservationStatus.FAILED, "Blender adapter closed without a response.")
        if (header.startsWith("ERROR\t")) {
            val diagnostic = header.substringAfter('\t').decodeBase64() ?: "Blender adapter reported an unspecified error."
            return unavailable(BlenderObservationStatus.FAILED, diagnostic)
        }
        val fields = header.split('\t')
        if (fields.size != HEADER_FIELD_COUNT || fields[0] != PROTOCOL_HEADER) {
            return unavailable(BlenderObservationStatus.FAILED, "Blender adapter returned an unsupported protocol header.")
        }

        if (fields[7] !in setOf("true", "false")) {
            return unavailable(BlenderObservationStatus.FAILED, "Blender adapter returned an invalid completeness value.")
        }
        val sessionId = fields[1].decodeBase64()?.takeIf(String::isNotBlank)
            ?: return unavailable(BlenderObservationStatus.FAILED, "Blender adapter omitted its session identity.")
        val sceneId = fields[4].decodeBase64()?.takeIf(String::isNotBlank)
            ?: return unavailable(BlenderObservationStatus.FAILED, "Blender adapter omitted its scene identity.")
        val sceneName = fields[5].decodeBase64()?.takeIf(String::isNotBlank)
            ?: return unavailable(BlenderObservationStatus.FAILED, "Blender adapter omitted its scene name.")
        val blenderVersion = fields[3].decodeBase64()?.takeIf(String::isNotBlank)
            ?: return unavailable(BlenderObservationStatus.FAILED, "Blender adapter omitted its version.")
        val expectedCount = fields[8].toIntOrNull()
            ?: return unavailable(BlenderObservationStatus.FAILED, "Blender adapter returned an invalid object count.")
        if (expectedCount !in 0..MAX_OBJECTS) {
            return unavailable(BlenderObservationStatus.INSUFFICIENT, "Blender object inventory exceeded the supported limit.")
        }
        val complete = fields[7] == "true"
        val objects = ArrayList<ObjectObservation>(expectedCount)
        repeat(expectedCount) {
            val line = input.readUtf8Line()
                ?: return unavailable(BlenderObservationStatus.FAILED, "Blender adapter returned a truncated object inventory.")
            val objectFields = line.split('\t')
            if (objectFields.size != OBJECT_FIELD_COUNT || objectFields[0] != "OBJECT") {
                return unavailable(BlenderObservationStatus.FAILED, "Blender adapter returned a malformed object record.")
            }
            val id = objectFields[1].decodeBase64()?.takeIf(String::isNotBlank)
                ?: return unavailable(BlenderObservationStatus.FAILED, "Blender adapter returned an object without identity.")
            val name = objectFields[2].decodeBase64()?.takeIf(String::isNotBlank)
                ?: return unavailable(BlenderObservationStatus.FAILED, "Blender adapter returned an object without a name.")
            val type = objectFields[3].decodeBase64()?.takeIf(String::isNotBlank)
                ?: return unavailable(BlenderObservationStatus.FAILED, "Blender adapter returned an object without a type.")
            val objectSceneName = objectFields[4].decodeBase64()?.takeIf(String::isNotBlank)
                ?: return unavailable(BlenderObservationStatus.FAILED, "Blender adapter returned an object without scene identity.")
            val selected = when (objectFields[5]) {
                "true" -> true
                "false" -> false
                "unknown" -> null
                else -> return unavailable(BlenderObservationStatus.FAILED, "Blender adapter returned an invalid selection value.")
            }
            val location = objectFields[6].decodeBase64()
            if (complete && (selected == null || location.isNullOrBlank())) {
                return unavailable(BlenderObservationStatus.FAILED, "Blender adapter marked an object complete but omitted required properties.")
            }
            objects += ObjectObservation(
                id = id,
                name = name,
                type = type,
                sceneName = objectSceneName,
                selected = selected,
                metadata = location?.let { mapOf("location" to it) } ?: emptyMap()
            )
        }
        if (input.readUtf8Line() != "END") {
            return unavailable(BlenderObservationStatus.FAILED, "Blender adapter response did not terminate correctly.")
        }
        if (!complete) {
            return unavailable(
                BlenderObservationStatus.INSUFFICIENT,
                "Blender adapter returned an incomplete scene inventory.",
                mapOf("objectCount" to expectedCount.toString())
            )
        }
        if (objects.map { it.id }.toSet().size != objects.size) {
            return unavailable(BlenderObservationStatus.INSUFFICIENT, "Blender adapter returned duplicate object identities.")
        }

        return BlenderStateObservation(
            sceneName = sceneName,
            sceneId = sceneId,
            mode = fields[6].decodeBase64(),
            objects = objects,
            selectedObjects = objects.filter { it.selected == true }.mapNotNull { it.name }.toSet(),
            status = BlenderObservationStatus.AVAILABLE,
            capturedAt = Instant.parse(fields[2]),
            sessionId = sessionId,
            metadata = mapOf(
                "applicationName" to "Blender",
                "blenderVersion" to blenderVersion,
                "complete" to "true",
                "objectCount" to expectedCount.toString(),
                "transport" to "loopback-tcp-v1"
            )
        )
    }

    private fun unavailable(
        status: BlenderObservationStatus,
        diagnostic: String,
        metadata: Map<String, String> = emptyMap()
    ) = BlenderStateObservation(
        status = status,
        metadata = metadata + mapOf("provider" to "blender-loopback-tcp"),
        diagnostic = diagnostic
    )

    private fun String.decodeBase64(): String? {
        if (isEmpty()) return null
        return String(Base64.getUrlDecoder().decode(this), StandardCharsets.UTF_8)
    }

    private class ResponseReader(input: InputStream) {
        private val input = input.buffered()
        private var totalBytesRead = 0

        fun readUtf8Line(): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next == -1) return if (bytes.size() == 0) null else error("Truncated Blender adapter response.")
                totalBytesRead++
                if (totalBytesRead > MAX_RESPONSE_BYTES) error("Blender adapter response exceeded the supported limit.")
                if (next == '\n'.code) return bytes.toByteArray().toString(StandardCharsets.UTF_8).removeSuffix("\r")
                if (bytes.size() >= MAX_LINE_BYTES) error("Blender adapter response line exceeded the supported limit.")
                bytes.write(next)
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 47629
        const val DEFAULT_TIMEOUT_MILLIS = 1_500
        const val MAX_TIMEOUT_MILLIS = 60_000
        const val MAX_OBJECTS = 10_000
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val PROTOCOL_HEADER = "AIVT_STATE_V1"
        private const val HEADER_FIELD_COUNT = 9
        private const val OBJECT_FIELD_COUNT = 7
        private const val MAX_LINE_BYTES = 65_536
        private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        private val REQUEST = "AIVT_STATE_V1\n".toByteArray(StandardCharsets.US_ASCII)
    }
}
