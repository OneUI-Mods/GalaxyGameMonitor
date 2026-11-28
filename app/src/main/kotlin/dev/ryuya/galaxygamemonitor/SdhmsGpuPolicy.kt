package dev.ryuya.galaxygamemonitor

internal object SdhmsGpuPolicy {
    fun afterUpdate(oldBypass: Boolean, oldEnabled: Boolean, newBypass: Boolean, explicit: Boolean?): Boolean =
        newBypass && (explicit ?: (!oldBypass || oldEnabled))

    fun isGameRelease(scenario: String?, limiter: String?, temporary: Boolean, frequencies: IntArray?): Boolean =
        (scenario == "SPA" || scenario == "GameSDK") &&
            limiter == "GPUFreqMax" && temporary && frequencies?.size == 1 && frequencies[0] == -1
}
