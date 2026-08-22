package lechuck.intellij.vars

import javax.swing.DefaultCellEditor
import javax.swing.JTextField
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

    /** An escaped backslash ends the escape, so the '=' after it is a separator. */
    @Test
    fun testEscapedBackslashBeforeSeparator() {
        assertEquals(mapOf("""a\""" to "b"), parseVarsFromText("""a\\=b"""))
    }

    // --- separators escaped by the writer ---

    @Test
    fun testEscapedSeparatorStaysInValue() {
        assertEquals(mapOf("A" to "b;c"), parseVarsFromText("""A=b\;c"""))
    }

    @Test
    fun testEscapedSeparatorFollowedByRealSeparator() {
        assertEquals(linkedMapOf("A" to "b;c", "B" to "d"), parseVarsFromText("""A=b\;c;B=d"""))
    }

    @Test
    fun testEscapedSeparatorInSecondPair() {
        assertEquals(linkedMapOf("A" to "x", "B" to "y;z"), parseVarsFromText("""A=x;B=y\;z"""))
    }

    // --- backslashes are literal, not Java escapes ---

    @Test
    fun testWindowsPathIsNotUnescaped() {
        assertEquals(mapOf("A" to """C:\temp\new"""), parseVarsFromText("""A=C:\temp\new"""))
    }

    @Test
    fun testLoneBackslashIsPreserved() {
        assertEquals(mapOf("A" to """back\slash"""), parseVarsFromText("""A=back\slash"""))
        assertEquals(mapOf("A" to """trailing\"""), parseVarsFromText("""A=trailing\"""))
    }

    // --- cell editor ---

    /**
     * A line break would break the shell command task builds, so the cell editor must keep the
     * default newline filtering. StringWithNewLinesCellEditor, which the value column used to use,
     * turns it off.
     */
    @Test
    fun testCellEditorFiltersNewlines() {
        val editor = VariablesTable.createCellEditor() as DefaultCellEditor
        val document = (editor.component as JTextField).document
        assertEquals(true, document.getProperty("filterNewlines"))

        document.insertString(0, "a\nb", null)
        assertEquals("a b", document.getText(0, document.length))
    }

    // --- newlines are ordinary characters, not separators ---

    /**
     * ';' is the only separator. A newline is left in the value, where the run configuration will
     * pass it on to task; it is not treated as the start of another variable.
     */
    @Test
    fun testNewlineIsNotASeparator() {
        assertEquals(mapOf("a" to "b\nc=d"), parseVarsFromText("a=b\nc=d"))
        assertEquals(mapOf("a" to "b\r\nc=d"), parseVarsFromText("a=b\r\nc=d"))
    }

    /** A trailing newline from copied text must not turn into an extra variable. */
    @Test
    fun testTrailingNewlineDoesNotSplitPairs() {
        assertEquals(linkedMapOf("a" to "b", "c" to "d\n"), parseVarsFromText("a=b;c=d\n"))
    }

    /**
     * Pins platform-inherited behavior rather than a choice made here: the parser trims the key but
     * keeps the value as written, so padding around a key does not survive a round trip while
     * padding inside a value (a path with spaces, say) does.
     */
    @Test
    fun testParseTrimsKeyButNotValue() {
        assertEquals(mapOf("a" to "b"), parseVarsFromText(" a =b"))
        assertEquals(mapOf("a" to " b "), parseVarsFromText("a= b "))
    }

    // --- round trip through the writer ---

    private fun roundTrip(vars: Map<String, String>): Map<String, String> =
        parseVarsFromText(
            VariablesTextFieldWithBrowseButton.stringifyVars(VariablesData.create(vars))
        )

    /**
     * A naive assertEquals on the maps' toString would pass by accident here: both {"A=X": "c"} and
     * {"A": "X=c"} print as {A=X=c}. The keys are compared directly.
     */
    @Test
    fun testRoundTripKeyContainingEquals() {
        val result = roundTrip(mapOf("A=X" to "c"))

        assertEquals(setOf("A=X"), result.keys)
        assertEquals("c", result["A=X"])
    }

    @Test
    fun testRoundTripPlainValues() {
        val vars = linkedMapOf("A" to "1", "B" to "hello world")
        assertEquals(vars, roundTrip(vars))
    }

    @Test
    fun testRoundTripValueContainingSeparator() {
        val vars = mapOf("A" to "b;c")
        assertEquals(vars, roundTrip(vars))
    }

    @Test
    fun testRoundTripValueContainingBackslash() {
        val vars = linkedMapOf("A" to """C:\temp\new""", "B" to """back\slash""")
        assertEquals(vars, roundTrip(vars))
    }

    @Test
    fun testRoundTripPreservesOrder() {
        val vars = linkedMapOf("c" to "1", "a" to "2", "b" to "3")
        assertEquals(listOf("c", "a", "b"), roundTrip(vars).keys.toList())
    }

    /** A trailing backslash must not let the separator merge into the value. */
    @Test
    fun testRoundTripValueEndingWithBackslash() {
        val vars = linkedMapOf("A" to """C:\temp\""", "B" to "y")
        assertEquals(vars, roundTrip(vars))
    }

    @Test
    fun testRoundTripValueThatLooksEscaped() {
        val vars = linkedMapOf("A" to """a\;b""", "B" to """c\\d""", "C" to """e\=f""")
        assertEquals(vars, roundTrip(vars))
    }

    @Test
    fun testRoundTripEmptyValueBetweenPairs() {
        val vars = linkedMapOf("A" to "", "B" to "y")
        assertEquals(vars, roundTrip(vars))
    }

    // --- round trip through the copy action, which also escapes '=' ---

    private fun copyRoundTrip(vars: Map<String, String>): Map<String, String> =
        parseVarsFromText(VariablesTable.stringifyForCopy(vars.map { (k, v) -> Variable(k, v) }))

    @Test
    fun testCopyRoundTripEscapesNameContainingEquals() {
        val vars = linkedMapOf("A=X" to "c", "B" to "d")
        assertEquals(vars, copyRoundTrip(vars))
    }

    @Test
    fun testCopyRoundTripValueEndingWithBackslash() {
        val vars = linkedMapOf("A" to """x\""", "B" to "y")
        assertEquals(vars, copyRoundTrip(vars))
    }
}
