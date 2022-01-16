package lechuck.intellij.util

import lechuck.intellij.util.StringUtil.splitVars
import org.junit.Assert
import org.junit.Test

class StringUtilTest {
    @Test
    fun testSplitVarse() {
        Assert.assertEquals(emptyMap<String, String>(), splitVars(""))
        Assert.assertEquals(emptyMap<String, String>(), splitVars("  "))
        Assert.assertEquals(emptyMap<String, String>(), splitVars("foo"))
        Assert.assertEquals(mapOf("TEST" to ""), splitVars("TEST="))
        Assert.assertEquals(mapOf("TEST" to "test1"), splitVars("TEST=test1"))
        Assert.assertEquals(mapOf("TEST" to "foo bar", "TEST2" to "1 2 3"), splitVars("""TEST=foo bar;TEST2=1 2 3"""))
    }
}
