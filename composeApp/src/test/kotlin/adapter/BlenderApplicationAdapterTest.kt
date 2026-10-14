package adapter

import context.BlenderObservationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlenderApplicationAdapterTest {

    private val adapter = BlenderApplicationAdapter()

    @Test
    fun testValidSnapshotParsing() {
        val validJson = """
        {
          "schemaVersion": 1,
          "application": "Blender",
          "applicationVersion": "5.2.2",
          "sessionId": "sess-alpha-1",
          "snapshotVersion": 42,
          "observedAt": "2026-10-12T08:30:00Z",
          "scene": {
            "identity": "sess-alpha-1:scene:pointer1",
            "name": "Scene"
          },
          "activeObject": {
            "identity": "sess-alpha-1:uid:10",
            "name": "Cube",
            "type": "MESH"
          },
          "selectedObjects": ["Cube"],
          "activeMode": "OBJECT",
          "workspace": "Layout",
          "objects": [
            {
              "identity": "sess-alpha-1:uid:10",
              "name": "Cube",
              "type": "MESH",
              "sceneName": "Scene",
              "selected": true,
              "location": [1.0, 2.5, -0.5],
              "rotation": [0.0, 0.0, 0.0],
              "scale": [1.0, 1.0, 1.0],
              "visible": true
            }
          ],
          "isComplete": true
        }
        """.trimIndent()

        val result = adapter.parseSnapshotJson(validJson)
        assertTrue(result is AdapterSnapshotResult.Success)

        val snapshot = result.snapshot
        assertEquals(1, snapshot.schemaVersion)
        assertEquals("Blender", snapshot.applicationName)
        assertEquals("5.2.2", snapshot.applicationVersion)
        assertEquals("sess-alpha-1", snapshot.sessionId)
        assertEquals(42L, snapshot.snapshotVersion)
        assertEquals("Scene", snapshot.scene.name)
        assertEquals("OBJECT", snapshot.activeMode)
        assertEquals("Layout", snapshot.workspace)
        assertEquals(1, snapshot.objects.size)

        val cube = snapshot.objects[0]
        assertEquals("Cube", cube.name)
        assertEquals("sess-alpha-1:uid:10", cube.identity)
        assertEquals(true, cube.selected)
        assertEquals(listOf(1.0, 2.5, -0.5), cube.location)

        // Legacy conversion
        val legacy = snapshot.toBlenderStateObservation()
        assertEquals(BlenderObservationStatus.AVAILABLE, legacy.status)
        assertEquals("Scene", legacy.sceneName)
        assertEquals("42", legacy.metadata["snapshotVersion"])
    }

    @Test
    fun testMissingRequiredFieldFails() {
        val missingApp = """
        {
          "schemaVersion": 1,
          "sessionId": "sess-1",
          "snapshotVersion": 1
        }
        """.trimIndent()

        val result = adapter.parseSnapshotJson(missingApp)
        assertTrue(result is AdapterSnapshotResult.Error)
        assertEquals("MISSING_FIELD", result.errorCode)
    }

    @Test
    fun testUnsupportedSchemaVersionFails() {
        val futureSchema = """
        {
          "schemaVersion": 99,
          "application": "Blender",
          "sessionId": "sess-1",
          "snapshotVersion": 1
        }
        """.trimIndent()

        val result = adapter.parseSnapshotJson(futureSchema)
        assertTrue(result is AdapterSnapshotResult.Error)
        assertEquals("UNSUPPORTED_SCHEMA", result.errorCode)
    }

    @Test
    fun testMalformedJsonFails() {
        val malformed = "NOT_A_JSON_OBJECT"
        val result = adapter.parseSnapshotJson(malformed)
        assertTrue(result is AdapterSnapshotResult.Error)
        assertEquals("MALFORMED_JSON", result.errorCode)
    }

    @Test
    fun testSnapshotOrderingRejectsStaleOutOfOrderResponse() {
        val testAdapter = BlenderApplicationAdapter()

        val v10 = """
        {
          "schemaVersion": 1,
          "application": "Blender",
          "sessionId": "sess-ordering",
          "snapshotVersion": 10,
          "scene": { "name": "Scene" },
          "isComplete": true
        }
        """.trimIndent()

        val r1 = testAdapter.parseSnapshotJson(v10)
        assertTrue(r1 is AdapterSnapshotResult.Success)
        assertEquals(10L, r1.snapshot.snapshotVersion)

        // Now an older snapshot version 8 arrives out of order
        val v8 = """
        {
          "schemaVersion": 1,
          "application": "Blender",
          "sessionId": "sess-ordering",
          "snapshotVersion": 8,
          "scene": { "name": "Scene" },
          "isComplete": true
        }
        """.trimIndent()

        val r2 = testAdapter.parseSnapshotJson(v8)
        assertTrue(r2 is AdapterSnapshotResult.Stale)
        assertEquals(8L, r2.snapshotVersion)
        assertEquals(10L, r2.currentVersion)
    }

    @Test
    fun testUnavailableAdapterHandling() = kotlinx.coroutines.runBlocking {
        // Connect to a closed port
        val closedAdapter = BlenderApplicationAdapter(port = 65431, timeoutMillis = 200)
        val health = closedAdapter.checkHealth()
        assertEquals(AdapterHealthStatus.UNAVAILABLE, health.status)

        val snapshotResult = closedAdapter.takeSnapshot()
        assertTrue(snapshotResult is AdapterSnapshotResult.Unavailable)
    }

    @Test
    fun testProjectedObjectParsing() {
        val adapter = BlenderApplicationAdapter()
        val json = """
        {
          "status": "PROJECTED",
          "target": "Cube",
          "identity": "session:xyz:pointer:abc",
          "scene": "Scene",
          "visible": true,
          "selected": false,
          "bounds": {
            "x": 712.67,
            "y": 391.81,
            "width": 161.3,
            "height": 177.48
          },
          "center": {
            "x": 793.32,
            "y": 480.55
          }
        }
        """.trimIndent()

        val result = adapter.parseProjectedObjectJson(json)
        assertTrue(result is ProjectedObjectResult.Success)
        assertEquals("Cube", result.targetName)
        assertEquals("session:xyz:pointer:abc", result.identity)
        assertEquals(false, result.isSelected)
        assertEquals(712.67f, result.bounds.x)
        assertEquals(391.81f, result.bounds.y)
        assertEquals(161.3f, result.bounds.width)
        assertEquals(177.48f, result.bounds.height)
        assertFalse(result.bounds.isScreenCoordinates) // Window-relative within Blender
    }

    @Test
    fun testParseAmbiguousViewportJson() {
        val adapter = BlenderApplicationAdapter()
        val json = """
        {
          "status": "AMBIGUOUS_VIEWPORT",
          "target": "Cube",
          "viewportCount": 2,
          "error": "Multiple 3D viewports detected (2). Target viewport cannot be determined unambiguously.",
          "viewports": [
            {"index": 0, "x": 0, "y": 0, "width": 800, "height": 600, "isQuadView": false},
            {"index": 1, "x": 800, "y": 0, "width": 800, "height": 600, "isQuadView": false}
          ]
        }
        """.trimIndent()

        val result = adapter.parseProjectedObjectJson(json)
        assertTrue(result is ProjectedObjectResult.AmbiguousViewport)
        assertEquals("Cube", result.target)
        assertEquals(2, result.viewportCount)
        assertTrue(result.diagnostic.contains("Multiple 3D viewports detected"))
    }

    @Test
    fun testParseNotProjectedJson() {
        val adapter = BlenderApplicationAdapter()
        val json = """
        {
          "status": "OUTSIDE_VIEWPORT",
          "reason": "BEHIND_CAMERA",
          "target": "Cube",
          "error": "Object 'Cube' is completely behind the viewport camera."
        }
        """.trimIndent()

        val result = adapter.parseProjectedObjectJson(json)
        assertTrue(result is ProjectedObjectResult.NotProjected)
        assertEquals("Cube", result.target)
        assertEquals("BEHIND_CAMERA", result.reason)
        assertTrue(result.diagnostic.contains("behind the viewport camera"))
    }

    @Test
    fun testParseOcclusionAndClippingJson() {
        val adapter = BlenderApplicationAdapter()
        val json = """
        {
          "status": "PROJECTED",
          "target": "Cube",
          "identity": "session:test:1",
          "scene": "Scene",
          "visible": true,
          "selected": true,
          "visibility": "POTENTIALLY_OCCLUDED",
          "occludedBy": "Sphere",
          "isPartiallyClipped": true,
          "bounds": {
            "x": 100.0,
            "y": 120.0,
            "width": 80.0,
            "height": 90.0
          },
          "center": {
            "x": 140.0,
            "y": 165.0
          }
        }
        """.trimIndent()

        val result = adapter.parseProjectedObjectJson(json)
        assertTrue(result is ProjectedObjectResult.Success)
        assertEquals("Cube", result.targetName)
        assertEquals("POTENTIALLY_OCCLUDED", result.visibility)
        assertEquals("Sphere", result.occludedBy)
        assertTrue(result.isPartiallyClipped)
    }
}

