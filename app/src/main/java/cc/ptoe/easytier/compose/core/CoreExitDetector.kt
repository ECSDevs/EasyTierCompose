package cc.ptoe.easytier.compose.core

/**
 * Debounces core-death signals from the poll loops into a single verdict.
 *
 * The EasyTier core never sets error_msg when an instance dies at runtime
 * (latest_error is only written on startup failure), so the poll loops must
 * infer death from three weaker signals:
 *
 *  1. NetworkInstanceRunningInfo.running == false (pbjson omits the field
 *     entirely when false, so NetworkInfo.running == null on a present entry).
 *  2. The instance entry missing from collectNetworkInfos output entirely
 *     (NetworkInfo == null) — the manager dropped it.
 *  3. collectNetworkInfos throwing (JNI/JSON failure) — the native layer is
 *     unusable; treated like a missing entry.
 *
 * Signal 1 trips immediately once the instance has been observed alive
 * ([onPoll] saw running == true). Before that, running == false also covers
 * the Created-state window right after runNetworkInstance (the instance is
 * registered before its start task flips it to Starting), so it must not be
 * mistaken for death — there it counts toward the missing streak instead.
 * Signals 2/3 are always debounced: a single missed poll is normal, so they
 * must be observed on [missingThreshold] consecutive polls before tripping.
 * Any healthy observation resets the counters.
 */
internal class CoreExitDetector(private val missingThreshold: Int = DEFAULT_MISSING_THRESHOLD) {

    private var seenAlive = false
    private var missingStreak = 0

    /**
     * Feeds one poll observation. Returns true exactly once when the core is
     * declared dead; subsequent calls keep evaluating fresh observations
     * (the poll loop exits right after a true verdict).
     */
    fun onPoll(info: NetworkInfo?): Boolean {
        if (info?.running == true) {
            seenAlive = true
            missingStreak = 0
            return false
        }
        if (seenAlive && info != null) {
            // Present entry reporting not-running after we saw it alive: the
            // core's own death verdict — authoritative, no debounce.
            return true
        }
        missingStreak++
        if (missingStreak >= missingThreshold) {
            missingStreak = 0
            return true
        }
        return false
    }

    /** Clears all state; called when a new poll session begins. */
    fun reset() {
        seenAlive = false
        missingStreak = 0
    }

    companion object {
        // 3 consecutive misses at the poll cadence (2s while STARTING, 5s while
        // RUNNING) bound the false-teardown risk while keeping detection well
        // under half a minute.
        const val DEFAULT_MISSING_THRESHOLD = 3
    }
}
