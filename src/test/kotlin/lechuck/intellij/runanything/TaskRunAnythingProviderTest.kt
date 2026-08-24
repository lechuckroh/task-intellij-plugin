package lechuck.intellij.runanything

import com.intellij.ide.actions.runAnything.activity.RunAnythingProvider
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import lechuck.intellij.explorer.TaskDiscoveryCache
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Fixtures go through [com.intellij.testFramework.fixtures.CodeInsightTestFixture.addFileToProject]
 * rather than a plain java.io write, for the reason [lechuck.intellij.explorer.TaskfileFinderTest]
 * documents: this provider finds Taskfiles through [com.intellij.psi.search.FilenameIndex], which
 * only sees files under the light project's content root. Those live in the fixture's in-memory
 * file system, so the `task` CLI cannot read them and [lechuck.intellij.discovery.TaskDiscovery]
 * answers from its YAML fallback instead -- which is fine here, since what is under test is the
 * completion and command-resolution wiring, not which discovery backend produced the names.
 */
@RunWith(JUnit4::class)
class TaskRunAnythingProviderTest : BasePlatformTestCase() {
    // Constructed lazily rather than in a field initializer: RunAnythingNotifiableProvider's own
    // constructor looks up the platform's "Run Anything" notification group, which is only
    // available once the fixture's application is up (i.e. after setUp).
    private val provider by lazy { TaskRunAnythingProvider() }

    private val prefix
        get() = "${javaClass.simpleName}-${getName()}"

    /** Adds a Taskfile and waits for its discovery result to land in [TaskDiscoveryCache]. */
    private fun addTaskfile(relativePath: String, text: String): VirtualFile {
        val file = myFixture.addFileToProject("$prefix/$relativePath", text).virtualFile
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        awaitDiscovery(file)
        return file
    }

    private fun awaitDiscovery(file: VirtualFile) {
        val discovered = CountDownLatch(1)
        TaskDiscoveryCache.getInstance(project).ensureDiscovered(file) { discovered.countDown() }
        assertTrue("discovery did not finish", discovered.await(10, TimeUnit.SECONDS))
    }

    private fun dataContext() = DataContext {
        if (CommonDataKeys.PROJECT.`is`(it)) project else null
    }

    private fun completionsFor(pattern: String): List<String> =
        ApplicationManager.getApplication().runReadAction<List<String>> {
            provider.getValues(dataContext(), pattern)
        }

    @Test
    fun testCompletesTaskNamesAfterTheHelpCommand() {
        addTaskfile("Taskfile.yml", "version: '3'\ntasks:\n  build: echo b\n  test: echo t\n")

        assertEquals(listOf("task build", "task test"), completionsFor("task "))
    }

    @Test
    fun testCompletionKeepsAlreadyTypedNamesOut() {
        addTaskfile("Taskfile.yml", "version: '3'\ntasks:\n  build: echo b\n  test: echo t\n")

        assertEquals(listOf("task build test"), completionsFor("task build "))
    }

    /**
     * The base class treats every prefix of the help command as a match, so without the guard in
     * [TaskRunAnythingProvider.getValues] a single `t` keystroke would both suggest every task in
     * the project and kick off discovery for every Taskfile in it (one `task` subprocess each).
     */
    @Test
    fun testPrefixOfTheHelpCommandSuggestsNothing() {
        addTaskfile("Taskfile.yml", "version: '3'\ntasks:\n  build: echo b\n")

        assertEquals(emptyList<String>(), completionsFor("t"))
        assertEquals(emptyList<String>(), completionsFor("tas"))
        assertEquals(listOf("task build"), completionsFor("task"))
    }

    @Test
    fun testUnrelatedPatternIsNotOurs() {
        assertEquals(emptyList<String>(), completionsFor("gradle build"))
        assertNull(provider.findMatchingValue(dataContext(), "gradle build"))
    }

    /**
     * Pins the constraint that makes this provider safe to plug into the popup at all: the platform
     * computes completions inside a non-blocking read action, and discovery shells out to the
     * `task` CLI -- which `OSProcessHandler` forbids waiting on while the read lock is held. So an
     * uncached Taskfile must come back with *nothing* rather than with the names discovery would
     * eventually find; only once its result is cached do the names show up. Swapping `cachedResult`
     * for a blocking `awaitResult` fails the first assertion here.
     */
    @Test
    fun testCompletionSkipsUncachedTaskfilesInsteadOfAwaitingThem() {
        val file = addTaskfile("Taskfile.yml", "version: '3'\ntasks:\n  build: echo b\n")
        TaskDiscoveryCache.getInstance(project).invalidate(file.path)

        assertEquals(emptyList<String>(), completionsFor("task "))

        awaitDiscovery(file)
        assertEquals(listOf("task build"), completionsFor("task "))
    }

    @Test
    fun testResolvesTheTaskfileDeclaringTheFirstName() {
        val root = addTaskfile("Taskfile.yml", "version: '3'\ntasks:\n  build: echo b\n")
        val nested = addTaskfile("nested/Taskfile.yml", "version: '3'\ntasks:\n  deploy: echo d\n")

        assertEquals(
            ResolvedTaskCommand("build", root.path),
            TaskRunAnythingProvider.resolveCommand(project, listOf("build"), "build"),
        )
        assertEquals(
            ResolvedTaskCommand("deploy", nested.path),
            TaskRunAnythingProvider.resolveCommand(project, listOf("deploy"), "deploy"),
        )
    }

    /**
     * A name declared in two Taskfiles resolves to the shallower one, so the same input always runs
     * the same task.
     */
    @Test
    fun testTheSameNameInTwoTaskfilesResolvesToTheShallowerOne() {
        val root = addTaskfile("Taskfile.yml", "version: '3'\ntasks:\n  build: echo root\n")
        addTaskfile("nested/Taskfile.yml", "version: '3'\ntasks:\n  build: echo nested\n")

        assertEquals(
            ResolvedTaskCommand("build", root.path),
            TaskRunAnythingProvider.resolveCommand(project, listOf("build"), "build"),
        )
    }

    /**
     * A first token that is not a known task name -- a flag, a variable assignment, or a name whose
     * Taskfile has not been discovered yet -- still runs, against the shallowest Taskfile: the same
     * file `task` itself resolves from the project root. Refusing instead would make a pasted
     * command or a recent-commands entry on a cold cache silently fail.
     */
    @Test
    fun testFlagsAndUnknownNamesFallBackToTheShallowestTaskfile() {
        val root = addTaskfile("Taskfile.yml", "version: '3'\ntasks:\n  build: echo b\n")
        addTaskfile("nested/Taskfile.yml", "version: '3'\ntasks:\n  deploy: echo d\n")

        assertEquals(
            ResolvedTaskCommand("--list", root.path),
            TaskRunAnythingProvider.resolveCommand(project, listOf("--list"), "--list"),
        )
        assertEquals(
            ResolvedTaskCommand("VAR=1 build", root.path),
            TaskRunAnythingProvider.resolveCommand(
                project,
                listOf("VAR=1", "build"),
                "VAR=1 build",
            ),
        )
    }

    /**
     * The command text goes into the run configuration's task field exactly as typed. The platform
     * parses the popup's input with `keepQuotes = true`, so re-joining the parsed parameters would
     * escape those quotes a second time and `task` would see them as literal characters -- setting
     * VAR to `"a b"` rather than to `a b`.
     */
    @Test
    fun testQuotedArgumentsArePassedThroughAsTyped() {
        addTaskfile("Taskfile.yml", "version: '3'\ntasks:\n  build: echo b\n")

        val resolved =
            TaskRunAnythingProvider.resolveCommand(
                project,
                listOf("build", "VAR=\"a b\""),
                "build VAR=\"a b\"",
            )

        assertEquals("build VAR=\"a b\"", resolved!!.task)
    }

    @Test
    fun testNothingTypedAfterTheHelpCommandResolvesToNothing() {
        addTaskfile("Taskfile.yml", "version: '3'\ntasks:\n  build: echo b\n")

        assertNull(TaskRunAnythingProvider.resolveCommand(project, emptyList(), ""))
    }

    @Test
    fun testRegisteredOnTheExtensionPoint() {
        assertTrue(RunAnythingProvider.EP_NAME.extensionList.any { it is TaskRunAnythingProvider })
    }
}
