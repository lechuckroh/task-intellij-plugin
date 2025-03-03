package lechuck.intellij.util

import lechuck.intellij.util.RegexUtil.splitBySpace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RegexUtilTest {
    @Test
    fun testSplitBySpace() {
        assertEquals(emptyList<String>(), splitBySpace(null))
        assertEquals(emptyList<String>(), splitBySpace(""))
        assertEquals(listOf("a", "b"), splitBySpace("a b"))
        assertEquals(listOf("a", "a b"), splitBySpace("""a  "a b" """))
    }
}