package lechuck.intellij.explorer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TaskExplorerViewSettingsTest : BasePlatformTestCase() {
    // BasePlatformTestCase reuses one light project (and so one PropertiesComponent) across every
    // method in this class -- each test resets both settings first so an earlier test's toggle
    // can't leak into a later one.
    override fun setUp() {
        super.setUp()
        val settings = TaskExplorerViewSettings.getInstance(project)
        settings.showInternalTasks = false
        settings.showDescriptions = true
    }

    @Test
    fun testShowInternalTasksDefaultsToFalse() {
        assertFalse(TaskExplorerViewSettings.getInstance(project).showInternalTasks)
    }

    @Test
    fun testShowDescriptionsDefaultsToTrue() {
        assertTrue(TaskExplorerViewSettings.getInstance(project).showDescriptions)
    }

    @Test
    fun testTogglesPersistAcrossLookups() {
        val settings = TaskExplorerViewSettings.getInstance(project)

        settings.showInternalTasks = true
        settings.showDescriptions = false

        assertTrue(TaskExplorerViewSettings.getInstance(project).showInternalTasks)
        assertFalse(TaskExplorerViewSettings.getInstance(project).showDescriptions)
    }
}
