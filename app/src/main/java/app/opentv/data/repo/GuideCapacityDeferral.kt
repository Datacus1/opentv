package app.opentv.data.repo

/** Stored in existing outcome metadata; no new schema, URLs or duplicate retry state. */
object GuideCapacityDeferral {
    const val LEGACY = "Guide cache needs idle maintenance before adding new listings; cached listings retained."
    private const val PREFIX = "Guide waiting for idle cleanup; additional rows needed: "
    fun encode(required: Int): String = PREFIX + required.coerceAtLeast(0)
    fun required(result: String): Int? = result.takeIf { it.startsWith(PREFIX) }
        ?.removePrefix(PREFIX)?.toIntOrNull()?.takeIf { it >= 0 }
    fun isDeferred(result: String) = result == LEGACY || required(result) != null
    fun fits(result: String, feedRows: Int, totalRows: Int, feedLimit: Int, totalLimit: Int): Boolean {
        if (!isDeferred(result)) return false
        val need = required(result) ?: 1 // Legacy failure didn't record its actual required capacity.
        return feedRows.toLong() + need <= feedLimit && totalRows.toLong() + need <= totalLimit
    }
}
