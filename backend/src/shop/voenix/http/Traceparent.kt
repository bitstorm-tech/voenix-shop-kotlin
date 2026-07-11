package shop.voenix.http

import java.util.UUID

internal object Traceparent {
    fun continueOrCreate(value: String?): String {
        val parent =
            value
                ?.let(TRACEPARENT_PATTERN::matchEntire)
                ?.takeIf { match ->
                    match.groupValues[1].any { character -> character != '0' } &&
                        match.groupValues[2].any { character -> character != '0' }
                }
        val trace =
            parent
                ?.groupValues
                ?.get(1)
                ?: UUID.randomUUID().toString().replace("-", "")
        val span =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(16)
        val flags = parent?.groupValues?.get(3) ?: "00"
        return "00-$trace-$span-$flags"
    }

    private val TRACEPARENT_PATTERN = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
}
