package fuck.andes.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationDistillerTest {

    @Test
    fun extractsConclusionLinesFromDeliverable() {
        val result = ConversationDistiller.tryDistill(
            scenarioName = "去广告模块",
            deliverable = "已修复根因：LSPosed 识别失败是入口类混淆。决定采用清单文件规范并重新打包，当前版本 v2.0.6 已发布通过验证。",
        )
        assertNotNull(result)
        assertEquals("去广告模块", result!!.scenarioName)
        assertEquals(true, result.content.contains("根因"))
        assertEquals(true, result.content.contains("决定"))
    }

    @Test
    fun returnsNullWhenNoConclusionKeyword() {
        val result = ConversationDistiller.tryDistill("闲聊", "今天天气不错，哈哈")
        assertNull(result)
    }

    @Test
    fun returnsNullWhenBlank() {
        assertNull(ConversationDistiller.tryDistill("t", "   "))
    }

    @Test
    fun capsLinesAndLineLength() {
        val longLine = "结论：" + "长".repeat(300)
        val result = ConversationDistiller.tryDistill("t", longLine, maxLines = 1, maxLineChars = 40)
        assertNotNull(result)
        assertEquals(40, result!!.content.length)
    }

    @Test
    fun keepsOnlyMatchingLines() {
        val deliverable = "第一行普通描述\n关键结论是采用方案A\n第二行普通描述\n最终版本已发布"
        val result = ConversationDistiller.tryDistill("t", deliverable)
        assertNotNull(result)
        assertEquals(2, result!!.content.lines().size)
        assertEquals(true, result.content.contains("采用"))
        assertEquals(true, result.content.contains("发布"))
        assertEquals(false, result.content.contains("普通描述"))
    }

    @Test
    fun trimsAndTakesLimitedLines() {
        val deliverable = generateSequence { "结论: 一条结论" }.take(10).joinToString("\n")
        val result = ConversationDistiller.tryDistill("t", deliverable, maxLines = 3)
        assertNotNull(result)
        assertEquals(3, result!!.content.lines().size)
    }
}
