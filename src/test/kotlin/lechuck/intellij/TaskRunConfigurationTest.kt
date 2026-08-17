package lechuck.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import lechuck.intellij.vars.VariablesData
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TaskRunConfigurationTest : BasePlatformTestCase() {

    private fun createConfiguration(): TaskRunConfiguration {
        val type = TaskRunConfigurationType()
        val factory = type.configurationFactories[0] as TaskConfigurationFactory
        return TaskRunConfiguration(project, factory, "test")
    }

    @Test
    fun testVariablesArePassedWithoutQuotes() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.variables = VariablesData.create(linkedMapOf("FOO" to "hello world", "BAR" to "x"))

        val params = cfg.buildCommandLine().parametersList.list

        assertTrue(
            "FOO=hello world should be a single argv element",
            params.contains("FOO=hello world"),
        )
        assertTrue("BAR=x should be a single argv element", params.contains("BAR=x"))
        assertFalse(
            "no variable argument should contain literal quotes: $params",
            params.any { it.startsWith("FOO=") && it.contains('"') },
        )
    }

    @Test
    fun testEmptyVariableValue() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.variables = VariablesData.create(linkedMapOf("EMPTY" to ""))

        val params = cfg.buildCommandLine().parametersList.list

        assertTrue("EMPTY= should be passed as-is", params.contains("EMPTY="))
    }

    @Test
    fun testCommandLineShape() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.variables = VariablesData.create(linkedMapOf("FOO" to "bar"))
        cfg.arguments = "--verbose"

        val cmd = cfg.buildCommandLine()
        val params = cmd.parametersList.list

        assertEquals(listOf("build", "FOO=bar", "--", "--verbose"), params)
        assertEquals("task", cmd.exePath)
    }
}
