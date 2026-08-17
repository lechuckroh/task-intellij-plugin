package lechuck.intellij.vars

import lechuck.intellij.vars.VariablesDialog.Companion.validationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VariablesDialogTest {

    @Test
    fun testAcceptsOrdinaryVariables() {
        assertNull(validationError("FOO", "bar"))
        assertNull(validationError("FOO", ""))
        assertNull(validationError("FOO", "hello world"))
        assertNull(validationError("FOO", "a;b"))
        assertNull(validationError("FOO", """C:\temp\new"""))
    }

    /** The trailing blank row of the table is not an error. */
    @Test
    fun testAcceptsFullyEmptyRow() {
        assertNull(validationError("", ""))
    }

    @Test
    fun testRejectsEmptyName() {
        assertNotNull(validationError("", "bar"))
    }

    /**
     * task interpolates variables into the shell command, so a line break ends it and the rest runs
     * as another command: `task FOO=$'line1\nline2'` fails with exit 127.
     */
    @Test
    fun testRejectsLineBreakInValue() {
        assertEquals(
            "Variable value cannot contain a line break: FOO",
            validationError("FOO", "line1\nline2"),
        )
        assertNotNull(validationError("FOO", "line1\r\nline2"))
        assertNotNull(validationError("FOO", "trailing\n"))
    }

    /** The offending text must not be interpolated into the message, or it wraps the balloon. */
    @Test
    fun testRejectsLineBreakInName() {
        assertEquals(
            "Variable name cannot contain a line break",
            validationError("FOO\nBAR", "baz"),
        )
    }
}
