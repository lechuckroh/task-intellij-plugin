package lechuck.intellij.util

import lechuck.intellij.util.RegexUtil.splitBySpace
import org.junit.Assert
import org.junit.Test

class RegexUtilTest {
    @Test
    fun testSplitBySpace() {
        Assert.assertEquals(emptyList<String>(), splitBySpace(null))
        Assert.assertEquals(emptyList<String>(), splitBySpace(""))
        Assert.assertEquals(listOf("a", "b"), splitBySpace("a b"))
        Assert.assertEquals(listOf("a", "a b"), splitBySpace("""a  "a b" """))
    }
}