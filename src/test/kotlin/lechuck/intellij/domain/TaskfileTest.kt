package lechuck.intellij.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class TaskfileTest {

    @Test
    fun `load simple task`() {
        // given
        val yaml = """
            version: '3'
            tasks:
              greet:
                cmds:
                  - echo Hello world!
              simple: echo Hello world!
        """.trimIndent()

        // when
        val taskfile = Taskfile.load(yaml.byteInputStream())

        // then
        val expected = Taskfile(
            tasks = mapOf(
                "greet" to Task(),
                "simple" to Task()
            )
        )
        assertEquals(expected, taskfile)
    }

    @Test
    fun `load duplicated tasks`() {
        // given
        val yaml = """
            version: '3'
            tasks:
              greet:
                cmds:
                  - echo Hello world!
              greet:
                cmds:
                  - echo Hello world!
        """.trimIndent()

        // when
        val taskfile = Taskfile.load(yaml.byteInputStream())

        // then
        val expected = Taskfile(tasks = mapOf("greet" to Task()))
        assertEquals(expected, taskfile)
    }

    @Test
    fun `include other taskfile`() {
        // given
        val path = "/tasks/Include.yml"
        val taskfileUrl = javaClass.getResource(path) ?: throw IllegalArgumentException("File not found: $path")
        val resourceFile = Paths.get(taskfileUrl.toURI()).toFile()
        val basePath = resourceFile.parentFile

        // when
        val taskfile = Taskfile.load(resourceFile.inputStream(), basePath)

        // then
        val expected = Taskfile(
            includes = mapOf(
                "dir1" to Include(taskfile = Taskfile(tasks = mapOf("greet" to Task()))),
                "dir2" to Include(taskfile = Taskfile(tasks = mapOf("greet" to Task()))),
            ),
            tasks = mapOf("greet" to Task())
        )
        assertEquals(expected, taskfile)
    }

    @Test
    fun `task names with namespaces`() {
        // given
        val taskfile = Taskfile(
            includes = mapOf(
                "depth1" to Include(
                    taskfile = Taskfile(
                        includes = mapOf(
                            "depth2" to Include(
                                taskfile = Taskfile(tasks = mapOf("greet3" to Task()))
                            )
                        ),
                        tasks = mapOf("greet2" to Task())
                    )
                )
            ),
            tasks = mapOf("greet1" to Task())
        )

        // when
        val taskNames = taskfile.getTaskNames()

        // then
        val expected = listOf("greet1", "depth1:greet2", "depth1:depth2:greet3")
        assertEquals(expected, taskNames)
    }
}