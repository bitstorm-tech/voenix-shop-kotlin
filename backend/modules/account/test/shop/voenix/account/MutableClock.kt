package shop.voenix.account

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A manually advanced clock for token-expiry and lockout-release tests. */
internal class MutableClock(start: Instant) : Clock() {
    private var current: Instant = start

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advanceBy(duration: Duration) {
        current += duration
    }
}
