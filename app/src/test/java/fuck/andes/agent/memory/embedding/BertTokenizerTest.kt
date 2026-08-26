package fuck.andes.agent.memory.embedding

import org.junit.Assert.assertEquals
import org.junit.Test

class BertTokenizerTest {

    private fun vocab(): Map<String, Int> {
        val m = HashMap<String, Int>()
        m["[PAD]"] = 0; m["[UNK]"] = 100; m["[CLS]"] = 101; m["[SEP]"] = 102; m["[MASK]"] = 103
        m["我"] = 1010; m["喜"] = 1011; m["欢"] = 1012; m["苹"] = 1013; m["果"] = 1014
        m["##果"] = 1015; m["hello"] = 2000; m["world"] = 2001
        return m
    }

    @Test
    fun 中文按字切分() {
        val t = BertTokenizer(vocab())
        assertEquals(listOf("我", "喜", "欢", "苹", "果"), t.tokenize("我喜欢苹果"))
    }

    @Test
    fun encode以CLS开头SEP结尾() {
        val t = BertTokenizer(vocab())
        val enc = t.encode("我喜欢")
        assertEquals(101, enc.inputIds[0])          // [CLS]
        assertEquals(102, enc.inputIds[4])          // [SEP]
        assertEquals(5, enc.attentionMask.takeWhile { it == 1 }.size)
    }

    @Test
    fun 短文本padding为0且mask标记有效区() {
        val t = BertTokenizer(vocab())
        val enc = t.encode("苹果", maxLen = 8)
        assertEquals(0, enc.inputIds[5])            // padding 区域
        assertEquals(0, enc.attentionMask[5])
        assertEquals(1, enc.attentionMask[1])       // 有效区
        assertEquals(8, enc.inputIds.size)
    }

    @Test
    fun 英文普通词按词保留() {
        val t = BertTokenizer(vocab())
        assertEquals(listOf("hello", "world"), t.tokenize("hello world"))
    }

    @Test
    fun 控制字符归一为空格() {
        val t = BertTokenizer(vocab())
        val toks = t.tokenize("我\u0001喜\u0002欢") // 控制字符
        assertEquals(listOf("我", "喜", "欢"), toks)
    }
}
