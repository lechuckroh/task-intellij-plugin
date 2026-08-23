package lechuck.intellij.explorer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Regression test for a real bug: [TaskfileGroupNode.update] shells out to the `task` CLI and
 * blocks waiting for it, and IntelliJ's own `OSProcessHandler` forbids that while the calling
 * thread holds the platform's read lock (blocking external I/O under that lock can stall every
 * write action in the IDE for as long as the wait takes). [StructureTreeModel]'s default invoker
 * holds that lock for the whole time a node computes, which is why [TaskExplorerPanel] builds its
 * tree with [com.intellij.util.concurrency.Invoker.forBackgroundThreadWithoutReadAction] instead --
 * this pins that invariant directly, rather than relying on driving the real (asynchronous)
 * `AsyncTreeModel`/`StructureTreeModel` pipeline end-to-end to observe it.
 */
@RunWith(JUnit4::class)
class TaskExplorerPanelTest : BasePlatformTestCase() {
    @Test
    fun testTreeModelsInvokerHoldsNoReadLock() {
        val panel = TaskExplorerPanel(project)
        try {
            val hasReadAccess =
                panel.treeModel.invoker
                    .compute { ApplicationManager.getApplication().isReadAccessAllowed }
                    .blockingGet(5, TimeUnit.SECONDS)

            assertEquals(false, hasReadAccess)
        } finally {
            Disposer.dispose(panel)
        }
    }
}
