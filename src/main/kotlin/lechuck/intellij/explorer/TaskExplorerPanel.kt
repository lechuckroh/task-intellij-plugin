package lechuck.intellij.explorer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.ui.PopupHandler
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.SimpleTreeStructure
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.EditSourceOnEnterKeyHandler
import com.intellij.util.concurrency.Invoker
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Slice 4/6 of the Task Explorer: double-click, Enter, or the context menu's "Run" item runs the
 * selected task, reusing [lechuck.intellij.TaskLineMarkerProvider.runTask] -- the same
 * prepare-a-configuration-and-execute-it path the gutter icon uses. The tree also refreshes itself
 * (debounced) when a Taskfile is created, deleted, renamed, or edited.
 */
internal class TaskExplorerPanel(private val project: Project) :
    JPanel(BorderLayout()), Disposable {
    // StructureTreeModel's own no-Invoker constructors default to
    // Invoker.forBackgroundThreadWithReadAction, which holds the read lock for the whole time a
    // node's update()/getChildren() runs. TaskfileGroupNode shells out to the `task` CLI from
    // there, and IntelliJ's own OSProcessHandler explicitly forbids waiting on a process while
    // holding that lock (it can stall every write action in the IDE for as long as the process
    // takes -- up to this class's 5s timeout). Building the tree with
    // forBackgroundThreadWithoutReadAction avoids that; anything under it that still needs read
    // access takes its own short-lived ReadAction instead (see TaskfileFinder.findAll and
    // TaskYamlDiscovery.discover).
    // internal rather than private so a test can pin the invariant this constructor establishes --
    // that computing this tree's nodes never holds the platform's read lock (see the comment
    // above).
    internal val treeModel =
        StructureTreeModel(
            object : SimpleTreeStructure() {
                private val root = TaskExplorerRootNode(project, ::scheduleRefresh)

                override fun getRootElement(): Any = root
            },
            null,
            Invoker.forBackgroundThreadWithoutReadAction(this),
            this,
        )

    private val tree = Tree(AsyncTreeModel(treeModel, this))

    // Disposed with the tree (not this panel's own Disposable), which is enough: the panel itself
    // has no lifecycle beyond the tree it wraps.
    private val refreshAlarm = Alarm(tree, this)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        // FilenameIndex (searched by TaskfileFinder) isn't available until indexing finishes;
        // getChildren() already comes back empty rather than throwing while that's in progress
        // (see TaskfileFinder), but the tree still needs telling to look again once it can.
        // Subscribed before the first updateEmptyText() call so a dumb-to-smart transition can't
        // land in the gap between the two and leave the "Indexing…" text stuck.
        project.messageBus
            .connect(this)
            .subscribe(
                DumbService.DUMB_MODE,
                object : DumbService.DumbModeListener {
                    override fun exitDumbMode() {
                        updateEmptyText()
                        treeModel.invalidateAsync()
                    }
                },
            )
        updateEmptyText()

        // Filtered by filename rather than reacting to every VFS change: a project can have a lot
        // of unrelated file traffic, and only a create/delete/rename/edit of something matching
        // one of the 8 recognized Taskfile names could actually change what this tree should show.
        // Debounced (see refreshAlarm) since editing a Taskfile fires one event per keystroke's
        // autosave, not one for the whole edit.
        project.messageBus
            .connect(this)
            .subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        // Only the tree's *structure* (which Taskfiles exist) is this listener's
                        // business. Evicting the stale per-file discovery results behind those rows
                        // is TaskDiscoveryCache's own VFS listener's job, so it happens for every
                        // consumer of the cache rather than only while this panel exists.
                        if (events.any(::mayAffectTaskfiles)) {
                            scheduleRefresh()
                        }
                    }
                },
            )

        EditSourceOnDoubleClickHandler.install(tree) { runSelectedTask() }
        EditSourceOnEnterKeyHandler.install(tree) { runSelectedTask() }
        val goToDefinitionAction = GoToDefinitionAction()
        goToDefinitionAction.registerCustomShortcutSet(CommonShortcuts.getEditSource(), tree, this)
        PopupHandler.installFollowingSelectionTreePopup(
            tree,
            DefaultActionGroup(RunSelectedTaskAction(), goToDefinitionAction),
            "TaskExplorerPopup",
        )

        val toolbar =
            ActionManager.getInstance()
                .createActionToolbar(
                    "TaskExplorer",
                    DefaultActionGroup(
                        RefreshAction(),
                        ShowInternalTasksAction(),
                        ShowDescriptionsAction(),
                    ),
                    true,
                )
        toolbar.targetComponent = this

        add(toolbar.component, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    private fun updateEmptyText() {
        tree.emptyText.text =
            if (DumbService.isDumb(project)) "Indexing…" else "No Taskfile found in project"
    }

    private fun selectedTaskNode(): TaskNode? {
        val path = tree.selectionPath ?: return null
        return TreeUtil.getLastUserObject(TaskNode::class.java, path)
    }

    private fun runSelectedTask() {
        selectedTaskNode()?.run()
    }

    // A rename's own path already reflects the new name (so foo.yml -> Taskfile.yml is caught by
    // isRecognizedName(it.path) above), but a rename AWAY from a recognized name (Taskfile.yml ->
    // foo.yml) needs the old name too, or a stale group would linger in the tree.
    private fun mayAffectTaskfiles(event: VFileEvent): Boolean =
        TaskfileFinder.isRecognizedName(event.path.substringAfterLast('/')) ||
            (event is VFilePropertyChangeEvent &&
                event.isRename &&
                TaskfileFinder.isRecognizedName((event.oldValue as? String).orEmpty()))

    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ treeModel.invalidateAsync() }, REFRESH_DEBOUNCE_MS)
    }

    override fun dispose() {}

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 500
    }

    private inner class RefreshAction :
        AnAction("Refresh", "Refresh the task list", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            treeModel.invalidateAsync()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    private inner class RunSelectedTaskAction :
        AnAction("Run", "Run the selected task", AllIcons.Actions.Execute) {
        override fun actionPerformed(e: AnActionEvent) {
            runSelectedTask()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedTaskNode() != null
        }

        // update() reads the tree's Swing selection state, which BGT must not touch.
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class GoToDefinitionAction :
        AnAction(
            "Go to Definition",
            "Navigate to the selected task's definition",
            AllIcons.Actions.EditSource,
        ) {
        override fun actionPerformed(e: AnActionEvent) {
            selectedTaskNode()?.navigate(true)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedTaskNode()?.canNavigate() == true
        }

        // update() reads the tree's Swing selection state, which BGT must not touch.
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class ShowInternalTasksAction :
        ToggleAction(
            "Show Internal Tasks",
            "Show tasks marked internal: true, found by scanning each Taskfile's own YAML directly",
            AllIcons.General.Filter,
        ) {
        override fun isSelected(e: AnActionEvent): Boolean =
            TaskExplorerViewSettings.getInstance(project).showInternalTasks

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            TaskExplorerViewSettings.getInstance(project).showInternalTasks = state
            treeModel.invalidateAsync()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    private inner class ShowDescriptionsAction :
        ToggleAction(
            "Show Descriptions",
            "Show each task's desc: next to its name",
            AllIcons.Actions.Preview,
        ) {
        override fun isSelected(e: AnActionEvent): Boolean =
            TaskExplorerViewSettings.getInstance(project).showDescriptions

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            TaskExplorerViewSettings.getInstance(project).showDescriptions = state
            treeModel.invalidateAsync()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }
}
