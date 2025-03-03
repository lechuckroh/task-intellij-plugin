package lechuck.intellij.util

import lechuck.intellij.util.StringUtil.splitVars
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StringUtilTest {
    @Test
    fun testSplitVarse() {
        assertEquals(emptyMap<String, String>(), splitVars(""))
        assertEquals(emptyMap<String, String>(), splitVars("  "))
        assertEquals(emptyMap<String, String>(), splitVars("foo"))
        assertEquals(mapOf("TEST" to ""), splitVars("TEST="))
        assertEquals(mapOf("TEST" to "test1"), splitVars("TEST=test1"))
        assertEquals(mapOf("TEST" to "foo bar", "TEST2" to "1 2 3"), splitVars("""TEST=foo bar;TEST2=1 2 3"""))
    }
}
