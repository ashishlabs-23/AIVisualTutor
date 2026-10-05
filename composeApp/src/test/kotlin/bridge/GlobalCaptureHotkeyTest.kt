package bridge

import context.LifecycleEvent
import context.RecordingContextLogger
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobalCaptureHotkeyTest {
    private class FakePlatform(var result: HotkeyRegistrationResult) : HotkeyPlatform {
        var registrations = 0
        var unregistrations = 0
        var callback: ((HotkeyAction) -> Unit)? = null
        override suspend fun register(onAction: (HotkeyAction) -> Unit): HotkeyRegistrationResult { registrations++; callback = onAction; return result }
        override fun unregister() { unregistrations++ }
    }

    @Test fun registersOnceAndClosesIdempotently() = runBlocking {
        val platform = FakePlatform(HotkeyRegistrationResult(true, true))
        val logger = RecordingContextLogger()
        val manager = GlobalCaptureHotkey(platform, logger)
        assertNull(manager.start({}, {}))
        assertNull(manager.start({}, {}))
        assertEquals(1, platform.registrations)
        manager.close(); manager.close()
        assertEquals(1, platform.unregistrations)
        assertEquals(LifecycleEvent.HOTKEY_REGISTERED, logger.entries.single().event)
    }

    @Test fun partialAndTotalRegistrationFailuresAreReported() = runBlocking {
        val partial = GlobalCaptureHotkey(FakePlatform(HotkeyRegistrationResult(false, true)), RecordingContextLogger())
        assertNotNull(partial.start({}, {}))
        partial.close()
        val platform=FakePlatform(HotkeyRegistrationResult(false, false, "conflict"))
        val total = GlobalCaptureHotkey(platform, RecordingContextLogger())
        assertTrue(total.start({}, {})!!.contains("conflict"))
        assertTrue(total.start({}, {})!!.contains("conflict"))
        assertEquals(1,platform.registrations)
        total.close()
    }

    @Test fun hotkeyCallbackIsDispatchedOffPlatformThread() = runBlocking {
        val platform = FakePlatform(HotkeyRegistrationResult(true, true))
        val manager = GlobalCaptureHotkey(platform)
        val callerThread = Thread.currentThread().id
        val callbackThread = AtomicLong(-1)
        val invoked = CountDownLatch(1)
        manager.start({ callbackThread.set(Thread.currentThread().id); invoked.countDown() }, {})
        platform.callback!!(HotkeyAction.PREVIOUS_WINDOW)
        assertTrue(invoked.await(2, TimeUnit.SECONDS))
        assertTrue(callbackThread.get().toLong() != callerThread)
        manager.close()
    }
}
