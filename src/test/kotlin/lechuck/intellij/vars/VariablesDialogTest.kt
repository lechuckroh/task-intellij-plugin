package lechuck.intellij.vars

import lechuck.intellij.vars.VariablesDialog.Companion.validationError
import lechuck.intellij.vars.VariablesDialog.Companion.validationWarning
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
     * task splits a 'NAME=VALUE' argument on its first '=', so a '=' inside the name silently
     * produces a differently-named variable. The leading '=' case is the one the old
     * environment-variable rules let through on Windows.
     */
    @Test
    fun testRejectsEqualsInName() {
        assertEquals("Variable name cannot contain '='", validationError("A=B", "x"))
        assertNotNull(validationError("=FOO", "x"))
    }

    /** exec silently truncates an argument at a NUL byte. */
    @Test
    fun testRejectsNulCharacter() {
        assertNotNull(validationError("FOO", "a\u0000b"))
        assertNotNull(validationError("FO\u0000O", "bar"))
    }

    /** Names a Taskfile cannot reference as {{.NAME}} warn, and never block saving. */
    @Test
    fun testWarnsOnNamesTemplatesCannotReference() {
        assertNotNull(validationWarning("MY-VAR"))
        assertNotNull(validationWarning("1FOO"))
        assertNotNull(validationWarning("MY VAR"))

        assertNull(validationWarning("FOO"))
        assertNull(validationWarning("_x"))
        assertNull(validationWarning("MY_VAR2"))
        // Go template fields are Unicode-aware; an ASCII-only rule would flag a working name
        assertNull(validationWarning("\ubcc0\uc218"))
        // the trailing blank row of the table never warns
        assertNull(validationWarning(""))
    }

    /** The warning must stay a warning: these names were accepted before the check existed. */
    @Test
    fun testTemplateWarningIsNotABlockingError() {
        assertNull(validationError("MY-VAR", "x"))
        assertNull(validationError("1FOO", "x"))
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
