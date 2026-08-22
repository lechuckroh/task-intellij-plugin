package lechuck.intellij.vars

import kotlin.random.Random
import lechuck.intellij.vars.VariablesTable.Companion.parseVarsFromText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks that the text form is reversible, over every short combination of the characters that
 * carry meaning in it. The escaping rules are shared by two writers and one parser, so a change to
 * any of them can silently break values that merely look escaped.
 */
class RoundTripFuzzTest {

    private val alphabet = listOf("a", "\\", ";", "=", " ")

    /** How the variables are rendered into, and read back from, the text field. */
    private fun textFieldRoundTrip(vars: Map<String, String>) =
        parseVarsFromText(
            VariablesTextFieldWithBrowseButton.stringifyVars(VariablesData.create(vars))
        )

    /** How the copy action renders variables, and how a paste reads them back. */
    private fun copyRoundTrip(vars: Map<String, String>) =
        parseVarsFromText(VariablesTable.stringifyForCopy(vars.map { (k, v) -> Variable(k, v) }))

    /** Every string up to [maxLen] characters long, drawn from [alphabet]. */
    private fun strings(maxLen: Int): List<String> {
        var current = listOf("")
        val all = mutableListOf("")
        repeat(maxLen) {
            current = current.flatMap { s -> alphabet.map { s + it } }
            all.addAll(current)
        }
        return all
    }

    private fun assertNoFailures(failures: List<String>) {
        assertEquals(failures.take(10).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun testValuesRoundTripThroughTextField() {
        val failures =
            strings(3).mapNotNull { value ->
                val vars = linkedMapOf("A" to value, "B" to "y")
                val got = textFieldRoundTrip(vars)
                if (got == vars) null else "value=[$value] -> $got"
            }
        assertNoFailures(failures)
    }

    @Test
    fun testNamesAndValuesRoundTripThroughTextField() {
        val failures = mutableListOf<String>()
        for (name in strings(2)) {
            // an empty or padded name is not preserved: the parser trims and skips those
            if (name.isEmpty() || name != name.trim()) continue
            for (value in strings(2)) {
                val vars = linkedMapOf(name to value, "B" to "y")
                if (vars.size != 2) continue
                val got = textFieldRoundTrip(vars)
                if (got != vars) failures.add("name=[$name] value=[$value] -> $got")
            }
        }
        assertNoFailures(failures)
    }

    @Test
    fun testNamesAndValuesRoundTripThroughCopy() {
        val failures = mutableListOf<String>()
        for (name in strings(2)) {
            // an empty or padded name is not preserved: the parser trims and skips those
            if (name.isEmpty() || name != name.trim()) continue
            for (value in strings(2)) {
                val vars = linkedMapOf(name to value, "B" to "y")
                if (vars.size != 2) continue
                val got = copyRoundTrip(vars)
                if (got != vars) failures.add("name=[$name] value=[$value] -> $got")
            }
        }
        assertNoFailures(failures)
    }

    @Test
    fun testRandomMultiVariableRoundTrip() {
        val random = Random(20260817)
        val failures = mutableListOf<String>()
        repeat(2000) {
            val vars = LinkedHashMap<String, String>()
            repeat(random.nextInt(1, 4)) { i ->
                vars["K$i"] =
                    (0 until random.nextInt(0, 5)).joinToString("") {
                        alphabet[random.nextInt(alphabet.size)]
                    }
            }
            val got = textFieldRoundTrip(vars)
            if (got != vars) failures.add("$vars -> $got")
        }
        assertNoFailures(failures)
    }

    /** Input arrives from typing and from the clipboard, so no string may crash or hang. */
    @Test(timeout = 60_000)
    fun testParserAcceptsArbitraryInput() {
        val random = Random(7)
        val chars = "a=;\\\n\r ".toCharArray()
        repeat(20000) {
            val s =
                (0 until random.nextInt(0, 8)).joinToString("") {
                    chars[random.nextInt(chars.size)].toString()
                }
            parseVarsFromText(s)
        }
    }
}
