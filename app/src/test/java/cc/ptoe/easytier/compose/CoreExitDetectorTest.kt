package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.CoreExitDetector
import cc.ptoe.easytier.compose.core.NetworkInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreExitDetectorTest {

    private fun info(running: Boolean?) = NetworkInfo(
        virtualIpv4 = null,
        routes = emptyList(),
        error = null,
        peers = emptyList(),
        hostname = null,
        natType = null,
        running = running,
    )

    @Test
    fun `healthy polls never trip`() {
        val detector = CoreExitDetector()
        repeat(10) { assertFalse(detector.onPoll(info(running = true))) }
    }

    @Test
    fun `running false after seen alive trips immediately`() {
        val detector = CoreExitDetector()
        detector.onPoll(info(running = true))
        assertTrue(detector.onPoll(info(running = false)))
    }

    @Test
    fun `running field absent after seen alive trips immediately`() {
        // A present entry without "running" (pbjson omits proto3-default false)
        // is the same death signal as an explicit false.
        val detector = CoreExitDetector()
        detector.onPoll(info(running = true))
        assertTrue(detector.onPoll(info(running = null)))
    }

    @Test
    fun `running false before ever alive is debounced not immediate`() {
        // Right after runNetworkInstance the entry exists with running=false
        // (Created state) until the start task flips it — must not trip.
        val detector = CoreExitDetector()
        repeat(2) { assertFalse(detector.onPoll(info(running = false))) }
        assertTrue(detector.onPoll(info(running = false)))
    }

    @Test
    fun `missing entries trip after threshold consecutive polls`() {
        val detector = CoreExitDetector()
        repeat(CoreExitDetector.DEFAULT_MISSING_THRESHOLD - 1) {
            assertFalse(detector.onPoll(null))
        }
        assertTrue(detector.onPoll(null))
    }

    @Test
    fun `single missing entry between healthy polls stays benign`() {
        val detector = CoreExitDetector()
        detector.onPoll(info(running = true))
        assertFalse(detector.onPoll(null))
        assertFalse(detector.onPoll(info(running = true)))
        assertFalse(detector.onPoll(null))
        assertFalse(detector.onPoll(info(running = true)))
    }

    @Test
    fun `streak resets after a healthy observation`() {
        val detector = CoreExitDetector()
        repeat(2) { assertFalse(detector.onPoll(null)) }
        assertFalse(detector.onPoll(info(running = true)))
        repeat(2) { assertFalse(detector.onPoll(null)) }
        // Only the 3rd consecutive miss trips, the streak restarted.
        assertTrue(detector.onPoll(null))
    }

    @Test
    fun `reset clears seen-alive so startup window is protected again`() {
        val detector = CoreExitDetector()
        detector.onPoll(info(running = true))
        detector.reset()
        assertFalse(detector.onPoll(info(running = false)))
    }

    @Test
    fun `missing and not-running-before-alive share one streak`() {
        val detector = CoreExitDetector()
        assertFalse(detector.onPoll(null))
        assertFalse(detector.onPoll(info(running = false)))
        assertTrue(detector.onPoll(null))
    }

    @Test
    fun `custom threshold is honored`() {
        val detector = CoreExitDetector(missingThreshold = 1)
        assertTrue(detector.onPoll(null))
    }
}
