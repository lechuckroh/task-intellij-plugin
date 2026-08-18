package lechuck.intellij.util

import lechuck.intellij.util.StringUtil.splitVars
import org.junit.Assert
import org.junit.Test

class StringUtilTest {
    @Test
    fun testSplitVars() {
        Assert.assertEquals(emptyMap<String, String>(), splitVars(""))
        Assert.assertEquals(emptyMap<String, String>(), splitVars("  "))
        Assert.assertEquals(emptyMap<String, String>(), splitVars("foo"))
        Assert.assertEquals(mapOf("" to "v"), splitVars("=v"))
        Assert.assertEquals(mapOf("A" to "b=c"), splitVars("A=b=c"))
        Assert.assertEquals(mapOf("A" to "1"), splitVars("A=1;"))
        Assert.assertEquals(mapOf("A" to "2"), splitVars("A=1;A=2"))
        Assert.assertEquals(mapOf("TEST" to ""), splitVars("TEST="))
        Assert.assertEquals(mapOf("TEST" to "test1"), splitVars("TEST=test1"))
        Assert.assertEquals(
            mapOf("TEST" to "foo bar", "TEST2" to "1 2 3"),
            splitVars("""TEST=foo bar;TEST2=1 2 3"""),
        )
    }
}
