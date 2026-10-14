package adapter

import context.BlenderObservationStatus
import context.BlenderStateObservation
import context.BlenderStateProvider
import context.ObjectObservation
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production Kotlin client adapter for Blender using versioned loopback HTTP JSON communication.
 * Implements both [ApplicationAdapter] and [BlenderStateProvider] for deep, backward-compatible integration.
 */
open class BlenderApplicationAdapter(
    val port: Int = DEFAULT_PORT,
    val host: String = DEFAULT_HOST,
    val authToken: String? = null,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMillis))
        .build()
) : ApplicationAdapter, BlenderStateProvider {

    override val identity = AdapterIdentity(
        id = "blender-http-adapter",
        applicationName = "Blender",
        adapterVersion = "0.2.0",
        supportedSchemaVersion = 1
    )

    override val capabilities: Set<AdapterCapability> = setOf(
        AdapterCapability.OBJECT_STATE,
        AdapterCapability.SELECTION_STATE,
        AdapterCapability.MODE_STATE,
        AdapterCapability.LIVE_CHANGE_EVENTS
    )

    private var lastObservedVersion: Long = 0L
    private var activeSession: ApplicationSession? = null

    init {
        require(port in 1..65535) { "Port must be in 1..65535" }
        require(host == "127.0.0.1" || host == "localhost") { "Adapter must bind to loopback address" }
        require(timeoutMillis > 0) { "Timeout must be positive" }
    }

    private val baseUri: String get() = "http://$host:$port"

    override suspend fun checkHealth(): AdapterHealth = withContext(Dispatchers.IO) {
        val checkedAt = Instant.now()
        try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("$baseUri/api/v1/health"))
                .timeout(Duration.ofMillis(timeoutMillis))
                .GET()
            authToken?.let { requestBuilder.header("X-Session-Token", it) }

            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val json = response.body()
                val statusStr = extractString(json, "status")
                val versionStr = extractLong(json, "snapshotVersion")
                val sessId = extractString(json, "sessionId")
                AdapterHealth(
                    status = if (statusStr == "HEALTHY") AdapterHealthStatus.HEALTHY else AdapterHealthStatus.DEGRADED,
                    adapterId = identity.id,
                    applicationName = identity.applicationName,
                    sessionId = sessId,
                    lastSnapshotVersion = versionStr,
                    diagnostic = "Adapter healthy on $baseUri",
                    checkedAt = checkedAt,
                    details = mapOf("httpStatus" to "200")
                )
            } else {
                AdapterHealth(
                    status = AdapterHealthStatus.DEGRADED,
                    adapterId = identity.id,
                    applicationName = identity.applicationName,
                    diagnostic = "Health check returned HTTP ${response.statusCode()}: ${response.body()}",
                    checkedAt = checkedAt,
                    details = mapOf("httpStatus" to response.statusCode().toString())
                )
            }
        } catch (ex: ConnectException) {
            AdapterHealth(
                status = AdapterHealthStatus.UNAVAILABLE,
                adapterId = identity.id,
                applicationName = identity.applicationName,
                diagnostic = "No Blender adapter listening on $baseUri: ${ex.message}",
                checkedAt = checkedAt
            )
        } catch (ex: HttpTimeoutException) {
            AdapterHealth(
                status = AdapterHealthStatus.DEGRADED,
                adapterId = identity.id,
                applicationName = identity.applicationName,
                diagnostic = "Health check timed out after ${timeoutMillis}ms",
                checkedAt = checkedAt
            )
        } catch (ex: Exception) {
            AdapterHealth(
                status = AdapterHealthStatus.UNAVAILABLE,
                adapterId = identity.id,
                applicationName = identity.applicationName,
                diagnostic = "Health check error: ${ex.message}",
                checkedAt = checkedAt
            )
        }
    }

    override suspend fun currentSession(): ApplicationSession? = activeSession

    override suspend fun takeSnapshot(): AdapterSnapshotResult = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("$baseUri/api/v1/snapshot"))
                .timeout(Duration.ofMillis(timeoutMillis))
                .GET()
            authToken?.let { requestBuilder.header("X-Session-Token", it) }

            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> parseSnapshotJson(response.body())
                401 -> AdapterSnapshotResult.Error(
                    diagnostic = "Unauthorized: Session token rejected by Blender adapter.",
                    errorCode = "UNAUTHORIZED"
                )
                404 -> AdapterSnapshotResult.Error(
                    diagnostic = "Endpoint /api/v1/snapshot not found on Blender adapter.",
                    errorCode = "NOT_FOUND"
                )
                else -> AdapterSnapshotResult.Error(
                    diagnostic = "Blender adapter returned HTTP ${response.statusCode()}: ${response.body()}",
                    errorCode = "HTTP_${response.statusCode()}"
                )
            }
        } catch (ex: ConnectException) {
            AdapterSnapshotResult.Unavailable(
                diagnostic = "Blender adapter is unavailable on $baseUri: ${ex.message}",
                error = ex
            )
        } catch (ex: HttpTimeoutException) {
            AdapterSnapshotResult.Unavailable(
                diagnostic = "Blender snapshot request timed out after ${timeoutMillis}ms",
                error = ex
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            AdapterSnapshotResult.Error(
                diagnostic = "Failed to obtain snapshot: ${ex.message}",
                error = ex
            )
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("$baseUri/api/v1/close"))
                .timeout(Duration.ofMillis(1000))
                .POST(HttpRequest.BodyPublishers.noBody())
            authToken?.let { requestBuilder.header("X-Session-Token", it) }
            httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding())
        } catch (_: Exception) {
            // Best effort close
        }
        activeSession = null
    }

    /**
     * Backward-compatible implementation of [BlenderStateProvider.observe]
     * delegating to the versioned [takeSnapshot] method.
     */
    override suspend fun observe(): BlenderStateObservation {
        return when (val result = takeSnapshot()) {
            is AdapterSnapshotResult.Success -> result.snapshot.toBlenderStateObservation()
            is AdapterSnapshotResult.Stale -> BlenderStateObservation(
                status = BlenderObservationStatus.FAILED,
                metadata = mapOf("provider" to "blender-http-adapter", "stale" to "true"),
                diagnostic = "Received stale snapshot version ${result.snapshotVersion} (expected >= ${result.currentVersion})"
            )
            is AdapterSnapshotResult.Unavailable -> BlenderStateObservation(
                status = BlenderObservationStatus.UNAVAILABLE,
                metadata = mapOf("provider" to "blender-http-adapter"),
                diagnostic = result.diagnostic
            )
            is AdapterSnapshotResult.Error -> BlenderStateObservation(
                status = BlenderObservationStatus.FAILED,
                metadata = mapOf("provider" to "blender-http-adapter", "errorCode" to (result.errorCode ?: "UNKNOWN")),
                diagnostic = result.diagnostic
            )
        }
    }

    fun parseSnapshotJson(json: String): AdapterSnapshotResult {
        try {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                return AdapterSnapshotResult.Error("Malformed JSON response from adapter.", "MALFORMED_JSON")
            }

            val schemaVersion = extractInt(json, "schemaVersion")
            if (schemaVersion != 1) {
                return AdapterSnapshotResult.Error("Unsupported schema version: $schemaVersion", "UNSUPPORTED_SCHEMA")
            }

            val app = extractString(json, "application")
            if (app.isBlank()) {
                return AdapterSnapshotResult.Error("Missing required field 'application'.", "MISSING_FIELD")
            }

            val appVersion = extractString(json, "applicationVersion")
            val sessionId = extractString(json, "sessionId")
            if (sessionId.isBlank()) {
                return AdapterSnapshotResult.Error("Missing required field 'sessionId'.", "MISSING_FIELD")
            }

            val snapshotVersion = extractLong(json, "snapshotVersion")
            if (snapshotVersion <= 0L) {
                return AdapterSnapshotResult.Error("Invalid or missing 'snapshotVersion'.", "INVALID_VERSION")
            }

            // Ordering validation: reject stale out-of-order snapshots
            if (snapshotVersion < lastObservedVersion) {
                return AdapterSnapshotResult.Stale(
                    snapshotVersion = snapshotVersion,
                    currentVersion = lastObservedVersion,
                    reason = "Snapshot version $snapshotVersion arrived out-of-order after version $lastObservedVersion"
                )
            }
            lastObservedVersion = maxOf(lastObservedVersion, snapshotVersion)

            val observedAtStr = extractString(json, "observedAt")
            val observedAt = runCatching { Instant.parse(observedAtStr) }.getOrDefault(Instant.now())

            // Parse scene
            val sceneIdentity = extractNestedString(json, "scene", "identity").ifBlank { "$sessionId:scene:unknown" }
            val sceneName = extractNestedString(json, "scene", "name").ifBlank { "Scene" }
            val scene = AdapterSceneIdentity(identity = sceneIdentity, name = sceneName)

            // Update session
            activeSession = ApplicationSession(
                sessionId = sessionId,
                applicationName = app,
                applicationVersion = appVersion,
                activeSceneId = sceneIdentity,
                activeSceneName = sceneName
            )

            // Parse activeObject
            val activeObjName = extractNestedString(json, "activeObject", "name").takeIf(String::isNotBlank)
            val activeObj = activeObjName?.let { name ->
                AdapterObjectObservation(
                    identity = extractNestedString(json, "activeObject", "identity"),
                    name = name,
                    type = extractNestedString(json, "activeObject", "type").ifBlank { "UNKNOWN" },
                    sceneName = sceneName
                )
            }

            // Parse selectedObjects
            val selectedObjects = extractStringArray(json, "selectedObjects").toSet()

            val activeMode = extractString(json, "activeMode").takeIf(String::isNotBlank) ?: "OBJECT"
            val workspace = extractString(json, "workspace").takeIf(String::isNotBlank)
            val isComplete = extractBoolean(json, "isComplete")

            // Parse objects array
            val objects = parseObjectsArray(json, sceneName)

            val snapshot = AdapterSnapshot(
                schemaVersion = schemaVersion,
                applicationName = app,
                applicationVersion = appVersion,
                sessionId = sessionId,
                snapshotVersion = snapshotVersion,
                observedAt = observedAt,
                scene = scene,
                activeObject = activeObj,
                selectedObjects = selectedObjects,
                activeMode = activeMode,
                workspace = workspace,
                objects = objects,
                isComplete = isComplete,
                metadata = mapOf(
                    "objectCount" to objects.size.toString(),
                    "transport" to "loopback-http-json-v1"
                )
            )

            return AdapterSnapshotResult.Success(snapshot)
        } catch (ex: Exception) {
            return AdapterSnapshotResult.Error("Error parsing adapter JSON response: ${ex.message}", "PARSE_EXCEPTION", ex)
        }
    }

    private fun parseObjectsArray(json: String, defaultScene: String): List<AdapterObjectObservation> {
        val objectsIndex = json.indexOf("\"objects\"")
        if (objectsIndex == -1) return emptyList()

        val openBracket = json.indexOf('[', objectsIndex)
        val closeBracket = json.lastIndexOf(']')
        if (openBracket == -1 || closeBracket <= openBracket) return emptyList()

        val objectsContent = json.substring(openBracket + 1, closeBracket)
        val objectBlocks = Regex("\\{([^}]+)\\}").findAll(objectsContent)

        return objectBlocks.mapNotNull { match ->
            val block = match.groupValues[1]
            fun prop(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: ""
            fun boolProp(k: String) = Regex("\"$k\"\\s*:\\s*(true|false)").find(block)?.groupValues?.get(1)?.toBooleanStrictOrNull()

            val name = prop("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val id = prop("identity").ifBlank { name }
            val type = prop("type").ifBlank { "UNKNOWN" }
            val scene = prop("sceneName").ifBlank { defaultScene }
            val sel = boolProp("selected")
            val vis = boolProp("visible") ?: true

            // Location
            val locMatch = Regex("\"location\"\\s*:\\s*\\[([^\\]]+)\\]").find(block)
            val loc = locMatch?.groupValues?.get(1)?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }

            val rotMatch = Regex("\"rotation\"\\s*:\\s*\\[([^\\]]+)\\]").find(block)
            val rot = rotMatch?.groupValues?.get(1)?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }

            val scaleMatch = Regex("\"scale\"\\s*:\\s*\\[([^\\]]+)\\]").find(block)
            val scale = scaleMatch?.groupValues?.get(1)?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }

            AdapterObjectObservation(
                identity = id,
                name = name,
                type = type,
                sceneName = scene,
                selected = sel,
                location = loc,
                rotation = rot,
                scale = scale,
                properties = listOfNotNull(
                    loc?.let { "location" to it.joinToString(",") },
                    rot?.let { "rotation" to it.joinToString(",") },
                    scale?.let { "scale" to it.joinToString(",") },
                    "visible" to vis.toString()
                ).toMap()
            )
        }.toList()
    }

    // Lightweight robust JSON field extractors
    private fun extractString(json: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""

    private fun extractInt(json: String, key: String): Int =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun extractLong(json: String, key: String): Long =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun extractBoolean(json: String, key: String): Boolean =
        Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false

    private fun extractNestedString(json: String, parentKey: String, childKey: String): String {
        val parentIndex = json.indexOf("\"$parentKey\"")
        if (parentIndex == -1) return ""
        val blockEnd = json.indexOf('}', parentIndex)
        if (blockEnd == -1) return ""
        val parentSub = json.substring(parentIndex, blockEnd + 1)
        return extractString(parentSub, childKey)
    }

    private fun extractStringArray(json: String, key: String): List<String> {
        val match = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]").find(json) ?: return emptyList()
        val content = match.groupValues[1]
        return Regex("\"([^\"]*)\"").findAll(content).map { it.groupValues[1] }.toList()
    }

    open suspend fun projectObject(targetName: String): ProjectedObjectResult = projectObject(targetName, null)

    open suspend fun projectObject(targetName: String, viewportIndex: Int?): ProjectedObjectResult = withContext(Dispatchers.IO) {
        try {
            val encodedTarget = java.net.URLEncoder.encode(targetName.trim(), java.nio.charset.StandardCharsets.UTF_8)
            val uriStr = if (viewportIndex != null) {
                "$baseUri/api/v1/project?target=$encodedTarget&viewport=$viewportIndex"
            } else {
                "$baseUri/api/v1/project?target=$encodedTarget"
            }
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(uriStr))
                .timeout(Duration.ofMillis(timeoutMillis))
                .GET()
            authToken?.let { requestBuilder.header("X-Session-Token", it) }

            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> parseProjectedObjectJson(response.body())
                404 -> parseProjectedObjectJson(response.body()).takeIf { it !is ProjectedObjectResult.Error }
                    ?: ProjectedObjectResult.NotFound(targetName, "Object not found in active Blender scene.")
                422 -> parseProjectedObjectJson(response.body()).takeIf { it !is ProjectedObjectResult.Error }
                    ?: ProjectedObjectResult.NotProjected(targetName, "Object outside active 3D viewport or ambiguous.")
                else -> ProjectedObjectResult.Error("Blender adapter returned HTTP ${response.statusCode()}: ${response.body()}")
            }
        } catch (ex: Exception) {
            ProjectedObjectResult.Error("Failed to project object '$targetName': ${ex.message}")
        }
    }

    fun parseProjectedObjectJson(json: String): ProjectedObjectResult {
        val status = extractString(json, "status")
        val target = extractString(json, "target")
        val errorMsg = extractString(json, "error")

        if (status == "AMBIGUOUS_VIEWPORT") {
            val count = extractInt(json, "viewportCount").takeIf { it > 0 } ?: 2
            val diag = errorMsg.ifBlank { "Multiple 3D viewports detected ($count); viewport is ambiguous." }
            return ProjectedObjectResult.AmbiguousViewport(target, count, diag)
        }

        if (status == "OUTSIDE_VIEWPORT" || status == "PROJECTION_UNAVAILABLE" || status == "NOT_PROJECTED") {
            val reason = extractString(json, "reason").ifBlank { status }
            val diag = errorMsg.ifBlank { "Object '$target' is outside active 3D viewport." }
            return ProjectedObjectResult.NotProjected(target, diag, reason)
        }

        if (status == "NOT_FOUND") {
            val diag = errorMsg.ifBlank { "Object '$target' not found in active Blender scene." }
            return ProjectedObjectResult.NotFound(target, diag)
        }

        val identity = extractString(json, "identity")
        val visible = extractBoolean(json, "visible") ?: true
        val selected = extractBoolean(json, "selected") ?: false
        val visibility = extractString(json, "visibility").ifBlank { "VISIBLE" }
        val occludedBy = extractString(json, "occludedBy").takeIf(String::isNotBlank)
        val isClipped = extractBoolean(json, "isPartiallyClipped") ?: (visibility == "PARTIALLY_CLIPPED")

        val boundsPattern = Regex("\"bounds\"\\s*:\\s*\\{([^\\}]*)\\}")
        val boundsMatch = boundsPattern.find(json)
        val boundsBlock = boundsMatch?.groupValues?.get(1).orEmpty()

        val x = extractDouble(boundsBlock, "x")?.toFloat() ?: 0f
        val y = extractDouble(boundsBlock, "y")?.toFloat() ?: 0f
        val w = extractDouble(boundsBlock, "width")?.toFloat() ?: 100f
        val h = extractDouble(boundsBlock, "height")?.toFloat() ?: 100f

        val region = models.HighlightRegion(
            x = x,
            y = y,
            width = w,
            height = h,
            isScreenCoordinates = false // Window-relative within Blender window
        )

        return ProjectedObjectResult.Success(
            targetName = target,
            identity = identity,
            bounds = region,
            isVisible = visible,
            isSelected = selected,
            visibility = visibility,
            occludedBy = occludedBy,
            isPartiallyClipped = isClipped
        )
    }

    open suspend fun fetchVersion(): AdapterVersionResult = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("$baseUri/api/v1/version"))
                .timeout(Duration.ofMillis(timeoutMillis))
                .GET()
            authToken?.let { requestBuilder.header("X-Session-Token", it) }

            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                parseVersionJson(response.body())
            } else {
                AdapterVersionResult.Error("Blender adapter returned HTTP ${response.statusCode()}: ${response.body()}")
            }
        } catch (ex: ConnectException) {
            AdapterVersionResult.Unavailable("Blender adapter is unavailable on $baseUri: ${ex.message}")
        } catch (ex: Exception) {
            AdapterVersionResult.Error("Failed to fetch version: ${ex.message}")
        }
    }

    fun parseVersionJson(json: String): AdapterVersionResult {
        val snapVer = extractLong(json, "snapshotVersion")
        val evtCounter = extractLong(json, "eventCounter")
        val sessId = extractString(json, "sessionId")
        val mode = extractString(json, "activeMode").ifBlank { "OBJECT" }
        val actObj = extractString(json, "activeObject").takeIf(String::isNotBlank)
        val selCount = extractInt(json, "selectedCount")
        return AdapterVersionResult.Success(
            AdapterVersionInfo(
                snapshotVersion = snapVer,
                eventCounter = evtCounter,
                sessionId = sessId,
                activeMode = mode,
                activeObject = actObj,
                selectedCount = selCount
            )
        )
    }

    private fun extractDouble(json: String, key: String): Double? {
        val pattern = Regex("\"$key\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
        return pattern.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    companion object {
        const val DEFAULT_PORT = 47629
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_TIMEOUT_MILLIS = 2000L
    }
}

/**
 * Version and event synchronization information from Blender.
 */
data class AdapterVersionInfo(
    val snapshotVersion: Long,
    val eventCounter: Long,
    val sessionId: String,
    val activeMode: String = "OBJECT",
    val activeObject: String? = null,
    val selectedCount: Int = 0
)

sealed class AdapterVersionResult {
    data class Success(val info: AdapterVersionInfo) : AdapterVersionResult()
    data class Unavailable(val diagnostic: String) : AdapterVersionResult()
    data class Error(val diagnostic: String) : AdapterVersionResult()
}

/**
 * Result of non-destructive 3D object projection from Blender's active viewport.
 */
sealed class ProjectedObjectResult {
    data class Success(
        val targetName: String,
        val identity: String,
        val bounds: models.HighlightRegion,
        val isVisible: Boolean,
        val isSelected: Boolean,
        val visibility: String = "VISIBLE",
        val occludedBy: String? = null,
        val isPartiallyClipped: Boolean = false
    ) : ProjectedObjectResult()

    data class AmbiguousViewport(
        val target: String,
        val viewportCount: Int,
        val diagnostic: String
    ) : ProjectedObjectResult()

    data class NotFound(val target: String, val diagnostic: String) : ProjectedObjectResult()

    data class NotProjected(
        val target: String,
        val diagnostic: String,
        val reason: String = "OUTSIDE_VIEWPORT"
    ) : ProjectedObjectResult()

    data class Error(val diagnostic: String) : ProjectedObjectResult()
}


/**
 * Extension mapping [AdapterSnapshot] to legacy [BlenderStateObservation].
 */
fun AdapterSnapshot.toBlenderStateObservation(): BlenderStateObservation = BlenderStateObservation(
    sceneName = scene.name,
    sceneId = scene.identity,
    mode = activeMode,
    objects = objects.map { it.toObjectObservation() },
    selectedObjects = selectedObjects,
    status = if (isComplete) BlenderObservationStatus.AVAILABLE else BlenderObservationStatus.INSUFFICIENT,
    capturedAt = observedAt,
    sessionId = sessionId,
    metadata = metadata + mapOf(
        "applicationName" to applicationName,
        "blenderVersion" to applicationVersion,
        "snapshotVersion" to snapshotVersion.toString(),
        "complete" to isComplete.toString(),
        "workspace" to (workspace ?: "Default")
    )
)
