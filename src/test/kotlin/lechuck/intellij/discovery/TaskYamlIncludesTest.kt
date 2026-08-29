package lechuck.intellij.discovery

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Covers the `includes:` walk added for the discovery role split: the YAML parser owns *what a
 * Taskfile says*, which means following local includes, and reporting -- never silently dropping --
 * the ones only the CLI could resolve.
 *
 * Fixtures go through the fixture's own file system, which is in-memory: the `task` CLI cannot read
 * it, which is exactly right here, since these tests are about the parser and not about the CLI.
 */
@RunWith(JUnit4::class)
class TaskYamlIncludesTest : BasePlatformTestCase() {
    private val prefix
        get() = "${javaClass.simpleName}-${getName()}"

    private fun add(relativePath: String, text: String): VirtualFile =
        myFixture.addFileToProject("$prefix/$relativePath", text).virtualFile

    private fun namesOf(file: VirtualFile) =
        TaskYamlDiscovery.discover(project, file).map { it.name }

    @Test
    fun testIncludedTasksAreNamespaced() {
        add("docs/Taskfile.yml", "version: '3'\ntasks:\n  build: echo docs\n")
        val root =
            add(
                "Taskfile.yml",
                "version: '3'\nincludes:\n  docs: ./docs/Taskfile.yml\ntasks:\n  build: echo root\n",
            )

        assertEquals(listOf("build", "docs:build"), namesOf(root))
    }

    /** An include naming a directory: Task looks for a Taskfile inside it. */
    @Test
    fun testIncludeOfADirectoryFindsTheTaskfileInside() {
        add("docs/Taskfile.yml", "version: '3'\ntasks:\n  serve: echo serve\n")
        val root = add("Taskfile.yml", "version: '3'\nincludes:\n  docs: ./docs\n")

        assertEquals(listOf("docs:serve"), namesOf(root))
    }

    @Test
    fun testNestedIncludesAreNamespacedAtEachLevel() {
        add("b/c/Taskfile.yml", "version: '3'\ntasks:\n  deep: echo deep\n")
        add("b/Taskfile.yml", "version: '3'\nincludes:\n  c: ./c/Taskfile.yml\n")
        val root = add("Taskfile.yml", "version: '3'\nincludes:\n  b: ./b/Taskfile.yml\n")

        assertEquals(listOf("b:c:deep"), namesOf(root))
    }

    @Test
    fun testFlattenDropsTheNamespace() {
        add("lib/Taskfile.yml", "version: '3'\ntasks:\n  shared: echo shared\n")
        val root =
            add(
                "Taskfile.yml",
                "version: '3'\nincludes:\n  lib:\n    taskfile: ./lib/Taskfile.yml\n    flatten: true\n",
            )

        assertEquals(listOf("shared"), namesOf(root))
    }

    @Test
    fun testExcludesDropTheNamedTasks() {
        add("lib/Taskfile.yml", "version: '3'\ntasks:\n  keep: echo keep\n  drop: echo drop\n")
        val root =
            add(
                "Taskfile.yml",
                "version: '3'\nincludes:\n  lib:\n    taskfile: ./lib/Taskfile.yml\n" +
                    "    excludes: [drop]\n",
            )

        assertEquals(listOf("lib:keep"), namesOf(root))
    }

    /** `internal: true` on the include makes everything it brings in uncallable. */
    @Test
    fun testInternalIncludeHidesItsTasks() {
        add("lib/Taskfile.yml", "version: '3'\ntasks:\n  helper: echo helper\n")
        val root =
            add(
                "Taskfile.yml",
                "version: '3'\nincludes:\n  lib:\n    taskfile: ./lib/Taskfile.yml\n" +
                    "    internal: true\ntasks:\n  build: echo build\n",
            )

        assertEquals(listOf("build"), namesOf(root))
        assertEquals(
            listOf("lib:helper"),
            TaskYamlDiscovery.discoverInternalOnly(project, root).map { it.name },
        )
    }

    /**
     * A cycle has to stop at the repeat. Without the visited set this recurses until the stack
     * gives out, which in an IDE means taking the whole tool window down with it.
     */
    @Test
    fun testCyclicIncludesTerminate() {
        add(
            "b/Taskfile.yml",
            "version: '3'\nincludes:\n  a: ../Taskfile.yml\ntasks:\n  bt: echo b\n",
        )
        val root =
            add(
                "Taskfile.yml",
                "version: '3'\nincludes:\n  b: ./b/Taskfile.yml\ntasks:\n  at: echo a\n",
            )

        assertEquals(listOf("at", "b:bt"), namesOf(root))
    }

    @Test
    fun testRemoteIncludeIsReportedNotGuessedAt() {
        val root =
            add(
                "Taskfile.yml",
                "version: '3'\nincludes:\n  shared: https://example.com/Taskfile.yml\n" +
                    "tasks:\n  build: echo build\n",
            )

        val result = TaskYamlDiscovery.discoverDetailed(project, root)

        assertEquals(listOf("build"), result.tasks.map { it.name })
        assertEquals(1, result.unresolvedIncludes.size)
        assertEquals(UnresolvedIncludeReason.REMOTE, result.unresolvedIncludes.single().reason)
        assertEquals("shared", result.unresolvedIncludes.single().namespace)
    }

    @Test
    fun testTemplatedIncludePathIsReportedAsDynamic() {
        val root = add("Taskfile.yml", "version: '3'\nincludes:\n  gen: '{{.DIR}}/Taskfile.yml'\n")

        assertEquals(
            UnresolvedIncludeReason.DYNAMIC,
            TaskYamlDiscovery.discoverDetailed(project, root).unresolvedIncludes.single().reason,
        )
    }

    @Test
    fun testMissingIncludeIsReportedUnlessOptional() {
        val required = add("Taskfile.yml", "version: '3'\nincludes:\n  gone: ./gone/Taskfile.yml\n")
        assertEquals(
            UnresolvedIncludeReason.MISSING,
            TaskYamlDiscovery.discoverDetailed(project, required)
                .unresolvedIncludes
                .single()
                .reason,
        )

        val optional =
            add(
                "opt/Taskfile.yml",
                "version: '3'\nincludes:\n  gone:\n    taskfile: ./gone/Taskfile.yml\n" +
                    "    optional: true\n",
            )
        assertEquals(
            emptyList<UnresolvedInclude>(),
            TaskYamlDiscovery.discoverDetailed(project, optional).unresolvedIncludes,
        )
    }

    /** The include graph the cache invalidates on -- every file that contributed, root included. */
    @Test
    fun testSourceFilesListEveryContributingTaskfile() {
        val included = add("docs/Taskfile.yml", "version: '3'\ntasks:\n  build: echo docs\n")
        val root = add("Taskfile.yml", "version: '3'\nincludes:\n  docs: ./docs/Taskfile.yml\n")

        assertEquals(
            setOf(root.path, included.path),
            TaskYamlDiscovery.discoverDetailed(project, root).sourceFiles,
        )
    }

    /**
     * A diamond -- two namespaces including the same file -- is not a cycle. Task lists the file's
     * tasks once per namespace (`a:shared` and `b:shared`), and dropping either would be exactly
     * the silent loss this parser reports unresolved includes to avoid.
     */
    @Test
    fun testAFileIncludedTwiceContributesUnderBothNamespaces() {
        add("lib/Taskfile.yml", "version: '3'\ntasks:\n  shared: echo shared\n")
        val root =
            add(
                "Taskfile.yml",
                "version: '3'\nincludes:\n  a: ./lib/Taskfile.yml\n  b: ./lib/Taskfile.yml\n" +
                    "tasks:\n  root: echo root\n",
            )

        assertEquals(listOf("root", "a:shared", "b:shared"), namesOf(root))
    }

    /**
     * `checksum:` pins the contents of an include; Task verifies it for local files just as it does
     * for downloaded ones. Reading it as "this must be remote" would drop every task of a pinned
     * local include -- tasks that run perfectly well.
     */
    @Test
    fun testAChecksumDoesNotMakeALocalIncludeRemote() {
        add("lib/Taskfile.yml", "version: '3'\ntasks:\n  pinned: echo pinned\n")
        val root =
            add(
                "Taskfile.yml",
                "version: '3'\nincludes:\n  lib:\n    taskfile: ./lib/Taskfile.yml\n" +
                    "    checksum: abc123\n",
            )

        val result = TaskYamlDiscovery.discoverDetailed(project, root)

        assertEquals(listOf("lib:pinned"), result.tasks.map { it.name })
        assertEquals(emptyList<UnresolvedInclude>(), result.unresolvedIncludes)
    }

    /**
     * `dir:` is where included tasks *run*, not where they are defined. Reading it as a Taskfile
     * path invents names: Task rejects a `dir:`-only include outright, so nothing it seems to offer
     * could ever be run.
     */
    @Test
    fun testADirOnlyIncludeIsReportedRatherThanTreatedAsATaskfilePath() {
        add("sub/Taskfile.yml", "version: '3'\ntasks:\n  st: echo st\n")
        val root = add("Taskfile.yml", "version: '3'\nincludes:\n  s:\n    dir: ./sub\n")

        val result = TaskYamlDiscovery.discoverDetailed(project, root)

        assertEquals(emptyList<String>(), result.tasks.map { it.name })
        assertEquals(UnresolvedIncludeReason.MISSING, result.unresolvedIncludes.single().reason)
    }

    /** `dir:` alongside `taskfile:` is just a working directory -- the include still resolves. */
    @Test
    fun testDirAlongsideTaskfileDoesNotPreventResolution() {
        add("sub/Taskfile.yml", "version: '3'\ntasks:\n  st: echo st\n")
        val root =
            add(
                "Taskfile.yml",
                "version: '3'\nincludes:\n  s:\n    taskfile: ./sub/Taskfile.yml\n" +
                    "    dir: ./other\n",
            )

        assertEquals(listOf("s:st"), namesOf(root))
    }

    /** A directory holding only a `.dist` Taskfile is a real include, not a missing one. */
    @Test
    fun testDirectoryIncludeFindsADistTaskfile() {
        add("sub/Taskfile.dist.yml", "version: '3'\ntasks:\n  dist: echo dist\n")
        val root = add("Taskfile.yml", "version: '3'\nincludes:\n  s: ./sub\n")

        assertEquals(listOf("s:dist"), namesOf(root))
    }

    /** Task takes an absolute include path as written; `findFileByRelativePath` would not. */
    @Test
    fun testAnAbsoluteIncludePathResolves() {
        val included = add("lib/Taskfile.yml", "version: '3'\ntasks:\n  abs: echo abs\n")
        val root = add("Taskfile.yml", "version: '3'\nincludes:\n  l: ${included.path}\n")

        assertEquals(listOf("l:abs"), namesOf(root))
    }

    /** An included task's alias is namespaced too -- a bare alias is not a name Task accepts. */
    @Test
    fun testIncludedTaskAliasesAreNamespaced() {
        add(
            "lib/Taskfile.yml",
            "version: '3'\ntasks:\n  build:\n    aliases: [b]\n    cmds: [echo b]\n",
        )
        val root = add("Taskfile.yml", "version: '3'\nincludes:\n  lib: ./lib/Taskfile.yml\n")

        assertEquals(listOf("lib:b"), TaskYamlDiscovery.discover(project, root).single().aliases)
    }
}
