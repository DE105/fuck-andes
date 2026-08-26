package fuck.andes.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemoryDistillRules 蒸馏规则单测（v6.0.1 增强版）。
 * 覆盖：负面偏好补词表、隐藏 bug 修复（"喜欢X"不误判为讨厌）、短句阈值、决定/进度蒸馏、边界过滤。
 */
class MemoryDistillRulesTest {

    private fun categories(message: String): String =
        MemoryDistillRules.extract(message).joinToString("|") { "${it.content}[${it.category}]" }

    private fun hasCategory(message: String, category: String): Boolean =
        MemoryDistillRules.extract(message).any { it.category == category }

    private fun hasContent(message: String, needle: String): Boolean =
        MemoryDistillRules.extract(message).any { it.content.contains(needle) }

    // ── 方向1：负面偏好补词表 ─────────────────────────────────
    @Test
    fun negativePreference_fan_getsDistilled() {
        // 用户真实表达"我最烦广告了"，原版无"烦"词 → 漏；增强版必须抓为 preference
        assertTrue(hasContent("我最烦广告了", "广告"))
        assertTrue(hasCategory("我最烦广告了", "preference"))
    }

    @Test
    fun negativePreference_taoyan_shortSentence_getsDistilled() {
        // "我讨厌广告" 5 字，原版被 MIN_SENTENCE_CHARS=6 过滤；增强版降阈值后应抓取
        assertTrue(hasContent("我讨厌广告", "广告"))
    }

    @Test
    fun negativePreference_fan2_shortGetsDistilled() {
        assertTrue(hasContent("我烦广告", "广告"))
    }

    @Test
    fun negativePreference_explicitNegative_getsDistilled() {
        assertTrue(hasContent("我不太喜欢加班", "加班"))
        assertTrue(hasContent("我受不了拖延", "拖延"))
    }

    // ── 隐藏 bug 修复：喜欢不能误判为讨厌 ─────────────────────
    @Test
    fun like_positive_isPreference_notMisclassifiedAsDislike() {
        val cats = categories("我喜欢吃火锅")
        assertTrue(hasContent("我喜欢吃火锅", "火锅"))
        assertTrue(hasCategory("我喜欢吃火锅", "preference"))
        // 内容与类别为一条 preference，而非"讨厌"误判（原版 DISLIKE 会误匹配"喜欢"）
        assertEquals(1, MemoryDistillRules.extract("我喜欢吃火锅").count { it.category == "preference" })
    }

    // ── 方向2：决定/进度/结论蒸馏 ─────────────────────────────
    @Test
    fun decision_statement_getsDistilledAsProject() {
        val cats = categories("决定：去广告模块采用 A+C 方案")
        assertTrue(cats.contains("A+C"))
        assertTrue(hasCategory("决定：去广告模块采用 A+C 方案", "project"))
    }

    @Test
    fun progress_statement_getsDistilledAsProject() {
        val cats = categories("进度：v5.4.7 已发布并验证生效")
        assertTrue(cats.contains("v5.4.7"))
        assertTrue(hasCategory("进度：v5.4.7 已发布并验证生效", "project"))
    }

    @Test
    fun decision_sentence_notMisclassifiedAsPreference() {
        // 决定句语义是 project，不应被当成"喜欢/讨厌"
        val facts = MemoryDistillRules.extract("我决定采用 A 方案")
        assertTrue(facts.any { it.category == "project" })
        assertFalse(facts.any { it.category == "preference" })
    }

    // ── 边界过滤（疑问/祈使/临时时间词跳过）──────────────────
    @Test
    fun question_skipped() {
        assertTrue(MemoryDistillRules.extract("你能帮我查下价格吗？").isEmpty())
    }

    @Test
    fun request_skipped() {
        assertTrue(MemoryDistillRules.extract("帮我看看这个方案").isEmpty())
    }

    @Test
    fun transientTime_skipped() {
        assertTrue(MemoryDistillRules.extract("我今天很忙").isEmpty())
    }

    // ── 原有稳定事实回流验证 ──────────────────────────────────
    @Test
    fun work_place_getsDistilled() {
        assertTrue(hasContent("我在深圳工作", "深圳"))
        assertTrue(hasCategory("我在深圳工作", "work"))
    }

    @Test
    fun name_getsDistilledWithProfile() {
        val facts = MemoryDistillRules.extract("我的名字是小明")
        assertTrue(facts.any { it.profileKey == "用户名字" && it.profileValue == "小明" })
    }
    // ── v6.0.2：短决定(<4字)也能被蒸馏为 project ─────────────────
    @Test
    fun decision_shortText_getsDistilledAsProject() {
        // "采用B方案" 决定客体仅 3 字，原 {4,120} 漏；改 {2,120} 必须抓
        val facts = MemoryDistillRules.extract("去广告模块采用B方案")
        assertTrue(facts.any { it.category == "project" && it.content.contains("采用B方案") })
    }

    @Test
    fun decision_anchorButNoObject_notMisclassifiedAsProject() {
        // "落地"后仅"了"1字，无明确客体，即使 {2,120} 也不应误抓为 project
        assertFalse(MemoryDistillRules.extract("已经落地了").any { it.category == "project" })
    }
    // ── v6.0.3：不带主语的极简短决定(5字)也应能蒸馏为 project ─────
    @Test
    fun decision_bareShort_getsDistilledAsProject() {
        // 用户实测：纯"采用B方案"(5字)；extract 层在 {2,120} 下必须命中
        assertTrue(MemoryDistillRules.extract("采用B方案").any { it.category == "project" })
    }
}
