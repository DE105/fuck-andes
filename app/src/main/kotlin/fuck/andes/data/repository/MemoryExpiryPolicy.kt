package fuck.andes.data.repository

import java.util.concurrent.TimeUnit

/**
 * 记忆过期淘汰策略（纯逻辑，可 kotlinc 单测）。
 *
 * 原则：长期未使用(updatedAt 久远)的原子/场景记忆视为噪音，自动清理，
 * 保证"越懂你"的同时不让历史噪音堆积拖累语义召回。活跃记忆（近期 updatedAt）受保护。
 * 实际删除由 MemoryLayerDao 按时间查询完成，本策略只负责判定与筛选。
 */
internal object MemoryExpiryPolicy {

    /** 原子记忆(L1)：默认保留 180 天。 */
    val DEFAULT_ATOM_TTL_MS: Long = TimeUnit.DAYS.toMillis(180)

    /** 场景记忆(L2)：默认保留 365 天。 */
    val DEFAULT_SCENARIO_TTL_MS: Long = TimeUnit.DAYS.toMillis(365)

    fun isAtomExpired(
        updatedAt: Long,
        now: Long,
        ttlMs: Long = DEFAULT_ATOM_TTL_MS,
    ): Boolean = (now - updatedAt) > ttlMs

    fun isScenarioExpired(
        updatedAt: Long,
        now: Long,
        ttlMs: Long = DEFAULT_SCENARIO_TTL_MS,
    ): Boolean = (now - updatedAt) > ttlMs

    /** 从 (id, updatedAt) 候选中筛出已过期的 id。 */
    fun expiredAtomIds(
        atoms: List<Pair<String, Long>>,
        now: Long,
        ttlMs: Long = DEFAULT_ATOM_TTL_MS,
    ): List<String> = atoms.filter { isAtomExpired(it.second, now, ttlMs) }.map { it.first }

    fun expiredScenarioIds(
        scenarios: List<Pair<String, Long>>,
        now: Long,
        ttlMs: Long = DEFAULT_SCENARIO_TTL_MS,
    ): List<String> = scenarios.filter { isScenarioExpired(it.second, now, ttlMs) }.map { it.first }
}
