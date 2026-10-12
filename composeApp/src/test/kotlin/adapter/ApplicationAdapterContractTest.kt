package adapter

import context.EvidenceSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationAdapterContractTest {

    @Test
    fun testAdapterIdentityAndSessionContracts() {
        val identity = AdapterIdentity(
            id = "blender-test-adapter",
            applicationName = "Blender",
            adapterVersion = "0.2.0",
            supportedSchemaVersion = 1
        )
        assertEquals("blender-test-adapter", identity.id)
        assertEquals("Blender", identity.applicationName)
        assertEquals(1, identity.supportedSchemaVersion)

        val session = ApplicationSession(
            sessionId = "sess-12345",
            applicationName = "Blender",
            applicationVersion = "5.2.2",
            activeSceneId = "sess-12345:scene:0",
            activeSceneName = "Scene",
            isAlive = true
        )
        assertEquals("sess-12345", session.sessionId)
        assertTrue(session.isAlive)
    }

    @Test
    fun testAdapterHealthStatus() {
        val health = AdapterHealth(
            status = AdapterHealthStatus.HEALTHY,
            adapterId = "blender-test-adapter",
            applicationName = "Blender",
            sessionId = "sess-123",
            lastSnapshotVersion = 42L,
            diagnostic = "Running normally",
            details = mapOf("ping" to "5ms")
        )
        assertEquals(AdapterHealthStatus.HEALTHY, health.status)
        assertEquals(42L, health.lastSnapshotVersion)
        assertEquals("Running normally", health.diagnostic)
    }

    @Test
    fun testEvidenceProvenanceAndCategoryDistinction() {
        val prov = EvidenceProvenance(
            observationId = "obs-1",
            category = EvidenceCategory.DIRECT_APPLICATION_STATE,
            source = EvidenceSource.BLENDER,
            snapshotVersion = 10L,
            sessionId = "sess-100",
            isDirectFact = true,
            freshness = ObservationFreshness.FRESH,
            confidence = 1.0
        )
        assertTrue(prov.isDirectFact)
        assertEquals(EvidenceCategory.DIRECT_APPLICATION_STATE, prov.category)
        assertEquals(ObservationFreshness.FRESH, prov.freshness)

        val obs = ProvenanceEvidenceObservation(
            provenance = prov,
            available = true,
            details = mapOf("objectCount" to "5")
        )
        val legacy = obs.toLegacyEvidenceObservation()
        assertEquals(EvidenceSource.BLENDER, legacy.source)
        assertTrue(legacy.available)
        assertEquals("DIRECT_APPLICATION_STATE", legacy.details["category"])
        assertEquals("true", legacy.details["isDirectFact"])
        assertEquals("10", legacy.details["snapshotVersion"])
    }

    @Test
    fun testAdapterObjectObservationConversion() {
        val obj = AdapterObjectObservation(
            identity = "sess:1:uid:42",
            name = "Cube",
            type = "MESH",
            sceneName = "Scene",
            selected = true,
            location = listOf(1.0, 2.0, 3.0),
            rotation = listOf(0.0, 0.0, 0.0),
            scale = listOf(1.0, 1.0, 1.0),
            visible = true,
            properties = mapOf("material" to "PrincipledBSDF")
        )
        val converted = obj.toObjectObservation()
        assertEquals("sess:1:uid:42", converted.id)
        assertEquals("Cube", converted.name)
        assertEquals("MESH", converted.type)
        assertEquals(true, converted.selected)
        assertEquals("1.0,2.0,3.0", converted.metadata["location"])
        assertEquals("PrincipledBSDF", converted.metadata["material"])
    }

    @Test
    fun testAdapterSnapshotToLegacyBlenderObservation() {
        val snapshot = AdapterSnapshot(
            schemaVersion = 1,
            applicationName = "Blender",
            applicationVersion = "5.2.2",
            sessionId = "sess-999",
            snapshotVersion = 15L,
            observedAt = Instant.now(),
            scene = AdapterSceneIdentity("sess-999:scene:1", "Scene"),
            activeMode = "OBJECT",
            workspace = "Modeling",
            selectedObjects = setOf("Cube"),
            objects = listOf(
                AdapterObjectObservation(
                    identity = "sess-999:uid:1",
                    name = "Cube",
                    type = "MESH",
                    sceneName = "Scene",
                    selected = true
                )
            ),
            isComplete = true
        )

        val legacy = snapshot.toBlenderStateObservation()
        assertEquals("Scene", legacy.sceneName)
        assertEquals("sess-999:scene:1", legacy.sceneId)
        assertEquals("OBJECT", legacy.mode)
        assertEquals(1, legacy.objects.size)
        assertEquals("Cube", legacy.objects[0].name)
        assertEquals(setOf("Cube"), legacy.selectedObjects)
        assertEquals("sess-999", legacy.sessionId)
        assertEquals("15", legacy.metadata["snapshotVersion"])
        assertEquals("Modeling", legacy.metadata["workspace"])
    }
}
