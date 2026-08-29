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

    /**
     * The string overload has no directory to resolve a relative include against, so it is the one
     * entry point that cannot follow `includes:` -- see [TaskYamlIncludesTest] for the file-based
     * walk that does.
     */
    @Test
    fun testTheStringOverloadCannotFollowIncludes() {
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

    /**
     * `~/x` is the home directory to Task as it is to a shell, and an include path that kept the
     * literal `~` would be resolved relative to the including Taskfile instead -- silently, since
     * that lookup simply fails and reports the include missing.
     */
    @Test
    fun testHomeRelativeIncludePathsAreExpanded() {
        val home = System.getProperty("user.home")

        assertEquals("$home/Taskfile.yml", TaskYamlDiscovery.expandHome("~/Taskfile.yml"))
        assertEquals("./lib/Taskfile.yml", TaskYamlDiscovery.expandHome("./lib/Taskfile.yml"))
        assertEquals("/abs/Taskfile.yml", TaskYamlDiscovery.expandHome("/abs/Taskfile.yml"))
        // Only the `~/` form: a directory that merely starts with a tilde is a normal path.
        assertEquals("~tmp/Taskfile.yml", TaskYamlDiscovery.expandHome("~tmp/Taskfile.yml"))
    }
}
