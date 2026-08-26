package fuck.andes.data.repository

import org.junit.Test
import org.junit.Assert.assertEquals

class MemoryExpiryPolicyTest {

    private val now = 1_000_000_000_000L
    private val day = 24L * 3600 * 1000

    @Test
    fun atomNotExpiredWithinTtl() {
        assertEquals(false, MemoryExpiryPolicy.isAtomExpired(now - 1000, now, 180 * day))
    }

    @Test
    fun atomExpiredAfterTtl() {
        assertEquals(true, MemoryExpiryPolicy.isAtomExpired(now - 181 * day, now, 180 * day))
    }

    @Test
    fun atomBoundaryNotExpiredExactlyTtl() {
        // exactly ttl 不算过期（> 而非 >=）
        assertEquals(false, MemoryExpiryPolicy.isAtomExpired(now - 180 * day, now, 180 * day))
    }

    @Test
    fun scenarioExpiredAfter365Days() {
        assertEquals(true, MemoryExpiryPolicy.isScenarioExpired(now - 366 * day, now))
    }

    @Test
    fun scenarioNotExpiredWithin365Days() {
        assertEquals(false, MemoryExpiryPolicy.isScenarioExpired(now - 30 * day, now))
    }

    @Test
    fun expiredAtomIdsFiltered() {
        val atoms = listOf(
            "old" to (now - 200 * day),
            "fresh" to (now - 1000),
        )
        assertEquals(listOf("old"), MemoryExpiryPolicy.expiredAtomIds(atoms, now))
    }

    @Test
    fun emptyWhenNoneExpired() {
        assertEquals(emptyList<String>(), MemoryExpiryPolicy.expiredAtomIds(listOf("a" to now), now))
    }

    @Test
    fun expiredScenarioIdsFiltered() {
        val scenarios = listOf(
            "old-scene" to (now - 400 * day),
            "active-scene" to now,
        )
        assertEquals(listOf("old-scene"), MemoryExpiryPolicy.expiredScenarioIds(scenarios, now))
    }

    @Test
    fun customTtlRespected() {
        val shortTtl = 10 * day
        assertEquals(true, MemoryExpiryPolicy.isAtomExpired(now - 11 * day, now, shortTtl))
        assertEquals(false, MemoryExpiryPolicy.isAtomExpired(now - 9 * day, now, shortTtl))
    }
}
