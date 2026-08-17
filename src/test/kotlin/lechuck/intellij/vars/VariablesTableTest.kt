package lechuck.intellij.vars

import lechuck.intellij.vars.VariablesTable.Companion.parseVarsFromText
import org.junit.Assert.assertEquals
import org.junit.Test

class VariablesTableTest {

    @Test
    fun testEmptyInput() {
        assertEquals(emptyMap<String, String>(), parseVarsFromText(null))
        assertEquals(emptyMap<String, String>(), parseVarsFromText(""))
    }

    @Test
    fun testInputWithoutSeparator() {
        assertEquals(emptyMap<String, String>(), parseVarsFromText("foo"))
        assertEquals(emptyMap<String, String>(), parseVarsFromText("foo;bar"))
    }

    @Test
    fun testSinglePair() {
        assertEquals(mapOf("a" to "b"), parseVarsFromText("a=b"))
    }

    @Test
    fun testMultiplePairs() {
        assertEquals(linkedMapOf("a" to "b", "c" to "d"), parseVarsFromText("a=b;c=d"))
    }

    /** Callers rely on user-specified iteration order, which Map.equals does not check. */
    @Test
    fun testKeyOrderIsPreserved() {
        assertEquals(listOf("c", "a", "b"), parseVarsFromText("c=1;a=2;b=3").keys.toList())
    }

    @Test
    fun testEmptyValue() {
        assertEquals(mapOf("a" to ""), parseVarsFromText("a="))
    }

    @Test
    fun testValueContainingSpaces() {
        assertEquals(mapOf("a" to "hello world"), parseVarsFromText("a=hello world"))
    }

    @Test
    fun testValueContainingEquals() {
        assertEquals(mapOf("a" to "b=c"), parseVarsFromText("a=b=c"))
    }

    @Test
    fun testLeadingSeparatorIsSkipped() {
        assertEquals(emptyMap<String, String>(), parseVarsFromText("=v"))
    }

    /**
     * Every '=' is escaped, so the pair has no separator. Must not throw
     * StringIndexOutOfBoundsException.
     */
    @Test
    fun testAllEqualsEscaped() {
        assertEquals(emptyMap<String, String>(), parseVarsFromText("""a\=b"""))
        assertEquals(emptyMap<String, String>(), parseVarsFromText("""a\=b\=c"""))
    }

    /** A pair with no usable separator must be skipped without discarding the remaining pairs. */
    @Test
    fun testAllEqualsEscapedFollowedByValidPair() {
        assertEquals(mapOf("c" to "d"), parseVarsFromText("""a\=b;c=d"""))
    }

    /** An escaped '=' belongs to the key; the next unescaped '=' is the separator. */
    @Test
    fun testEscapedEqualsInKey() {
        assertEquals(mapOf("a=b" to "c"), parseVarsFromText("""a\=b=c"""))
    }

    /**
     * The escape scan only inspects the single char before '=',
     * so an escaped backslash is misread as an escaped separator and the pair is dropped.
     */
    @Test
    fun testEscapedBackslashBeforeSeparatorIsDropped() {
        assertEquals(emptyMap<String, String>(), parseVarsFromText("""a\\=b"""))
    }
}
