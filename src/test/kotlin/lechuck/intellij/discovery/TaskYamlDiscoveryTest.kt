package lechuck.intellij.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskYamlDiscoveryTest {
    @Test
    fun testMapFormAndShortformTasksAreDiscovered() {
        val yaml =
            """
            tasks:
              build:
                desc: Build it
                cmds:
                  - echo build
              shortform: echo shortform
            """
                .trimIndent()

        assertEquals(listOf("build", "shortform"), TaskYamlDiscovery.discover(yaml).map { it.name })
    }

    /** includes: is the CLI's job -- this fallback only ever parses the one Taskfile it's given. */
    @Test
    fun testIncludedTasksAreNotDiscovered() {
        val yaml =
            """
            includes:
              docs: ./docs/Taskfile.yml
            tasks:
              build: echo build
            """
                .trimIndent()

        assertEquals(listOf("build"), TaskYamlDiscovery.discover(yaml).map { it.name })
    }

    @Test
    fun testMissingTasksSectionDiscoversNothing() {
        assertEquals(
            emptyList<String>(),
            TaskYamlDiscovery.discover("version: '3'\n").map { it.name },
        )
    }

    @Test
    fun testInvalidYamlDiscoversNothingRatherThanThrowing() {
        assertEquals(emptyList<String>(), TaskYamlDiscovery.discover("not: [valid").map { it.name })
    }

    @Test
    fun testDiscoverInternalOnlyFindsOnlyInternalTasks() {
        val yaml =
            """
            tasks:
              build: echo build
              hidden:
                internal: true
                cmds:
                  - echo hidden
              shortform-hidden: echo not internal
            """
                .trimIndent()

        val internalTasks = TaskYamlDiscovery.discoverInternalOnly(yaml)

        assertEquals(listOf("hidden"), internalTasks.map { it.name })
        assertEquals(true, internalTasks.single().isInternal)
    }

    @Test
    fun testDiscoverInternalOnlyIgnoresShortformTasks() {
        // A shortform task's value is a plain string command, never a map, so it can never carry
        // internal: true -- this documents that rather than leaving it implicit.
        val yaml = "tasks:\n  shortform: echo hi\n"

        assertEquals(
            emptyList<String>(),
            TaskYamlDiscovery.discoverInternalOnly(yaml).map { it.name },
        )
    }

    @Test
    fun testDiscoverInternalOnlyDiscoversNothingRatherThanThrowing() {
        assertEquals(
            emptyList<String>(),
            TaskYamlDiscovery.discoverInternalOnly("not: [valid").map { it.name },
        )
    }
}
