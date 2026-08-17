package lechuck.intellij

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.UnknownConfigurationType
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TaskLineMarkerProviderTest : BasePlatformTestCase() {
    private val provider = TaskLineMarkerProvider()

    // BasePlatformTestCase reuses one light project across the methods of this class, so run
    // configurations created by a test would otherwise leak into the next one. Each test below also
    // uses its own task name so it stays independent of execution order.
    override fun tearDown() {
        try {
            val runManager = RunManagerImpl.getInstanceImpl(project)
            runManager.removeConfigurations(runManager.allSettings.toList())
        } catch (e: Throwable) {
            // never let cleanup hide the real failure, e.g. when setUp itself failed
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    @Test
    fun testTaskfilePatternMatching() {
        val validNames =
            listOf(
                "Taskfile.yml",
                "taskfile.yml",
                "Taskfile.yaml",
                "taskfile.yaml",
                "Taskfile.dist.yml",
                "taskfile.dist.yml",
                "Taskfile.dist.yaml",
                "taskfile.dist.yaml",
            )

        val invalidNames =
            listOf("other.yml", "taskfile.json", "taskfile.yaml.bak", "mytaskfile.yml")

        validNames.forEach { name ->
            assertTrue("Should match: $name", name.matches(TaskLineMarkerProvider.TASKFILE_PATTERN))
        }

        invalidNames.forEach { name ->
            assertFalse(
                "Should not match: $name",
                name.matches(TaskLineMarkerProvider.TASKFILE_PATTERN),
            )
        }
    }

    @Test
    fun testGetInfoForValidTaskKey() {
        val yamlFile =
            """
            tasks:
              test:
                cmds:
                  - echo "test"
            """
                .trimIndent()

        val file = myFixture.configureByText("Taskfile.yml", yamlFile)
        val taskKey = findTaskKey(file, "test")

        assertNotNull(taskKey)
        val info = provider.getInfo(taskKey!!)
        assertNotNull("Should return Info for valid task key", info)
        assertEquals("Run Task: test", info?.tooltipProvider?.apply(taskKey))
    }

    @Test
    fun testGetInfoForNonTaskKey() {
        val yamlFile =
            """
            version: '3'
            tasks:
              test:
                cmds:
                  - echo "test"
            """
                .trimIndent()

        val file = myFixture.configureByText("Taskfile.yml", yamlFile)
        val versionKey = findKey(file, "version")

        assertNotNull(versionKey)
        val info = provider.getInfo(versionKey!!)
        assertNull("Should return null for non-task key", info)
    }

    @Test
    fun testGetInfoForShortFormTaskKey() {
        val yamlFile =
            """
            tasks:
              string-cmd: echo Short syntax command
              object-cmd:
                cmds:
                  - echo "object"
            """
                .trimIndent()

        val file = myFixture.configureByText("Taskfile.yml", yamlFile)
        val stringTaskKey = findTaskKey(file, "string-cmd")
        val objectTaskKey = findTaskKey(file, "object-cmd")

        assertNotNull(stringTaskKey)
        assertNotNull(objectTaskKey)

        val stringInfo = provider.getInfo(stringTaskKey!!)
        assertNotNull("Should return Info for short-form (string) task key", stringInfo)
        assertEquals("Run Task: string-cmd", stringInfo?.tooltipProvider?.apply(stringTaskKey))

        val objectInfo = provider.getInfo(objectTaskKey!!)
        assertNotNull("Should return Info for long-form (mapping) task key", objectInfo)
        assertEquals("Run Task: object-cmd", objectInfo?.tooltipProvider?.apply(objectTaskKey))
    }

    @Test
    fun testConfigurationIsCreatedFromTheRegisteredType() {
        val registeredType =
            ConfigurationTypeUtil.findConfigurationType(TaskRunConfigurationType::class.java)

        val settings =
            TaskLineMarkerProvider.prepareConfiguration(
                project,
                "registered-type",
                "/tmp/Taskfile.yml",
            )

        assertNotNull(settings)
        // a freshly constructed TaskRunConfigurationType() would supply a different factory object
        assertSame("Should use the platform-registered type", registeredType, settings!!.type)
        assertSame(
            "Should use the registered type's factory",
            registeredType.configurationFactories[0],
            settings.factory,
        )
        assertEquals("Task: registered-type", settings.name)

        // the new configuration has to be registered, not just constructed
        val runManager = RunManagerImpl.getInstanceImpl(project)
        assertTrue(
            "Should be registered with the RunManager",
            runManager.allSettings.contains(settings),
        )

        val runConfig = settings.configuration as TaskRunConfiguration
        assertEquals("registered-type", runConfig.task)
        assertEquals("/tmp/Taskfile.yml", runConfig.filename)
    }

    @Test
    fun testGutterRunReusesExistingTaskConfiguration() {
        val first =
            TaskLineMarkerProvider.prepareConfiguration(project, "reused", "/tmp/Taskfile.yml")
        val second =
            TaskLineMarkerProvider.prepareConfiguration(project, "reused", "/tmp/Taskfile.yml")

        assertNotNull(first)
        assertNotNull(second)
        assertSame("Running the same task twice should reuse one configuration", first, second)

        assertEquals(
            "Only one configuration should be registered",
            1,
            taskSettingsNamed("Task: reused").size,
        )

        val runConfig = second!!.configuration as TaskRunConfiguration
        assertEquals("reused", runConfig.task)
        assertEquals("/tmp/Taskfile.yml", runConfig.filename)
    }

    @Test
    fun testDifferentTasksGetTheirOwnConfigurations() {
        val build =
            TaskLineMarkerProvider.prepareConfiguration(project, "build", "/tmp/Taskfile.yml")
        val test = TaskLineMarkerProvider.prepareConfiguration(project, "test", "/tmp/Taskfile.yml")

        assertNotNull(build)
        assertNotNull(test)
        assertNotSame("Distinct tasks must not share one configuration", build, test)
        assertEquals("Task: build", build!!.name)
        assertEquals("Task: test", test!!.name)
        assertEquals(1, taskSettingsNamed("Task: build").size)
        assertEquals(1, taskSettingsNamed("Task: test").size)

        // the first configuration must not have been repointed at the second task
        assertEquals("build", (build.configuration as TaskRunConfiguration).task)
        assertEquals("test", (test.configuration as TaskRunConfiguration).task)
    }

    @Test
    fun testSameNamedConfigurationOfAnotherTypeIsIgnored() {
        val runManager = RunManagerImpl.getInstanceImpl(project)

        // a configuration of an unrelated type that happens to use the name the gutter action wants
        val foreignType = UnknownConfigurationType.getInstance()
        val foreign =
            runManager.createConfiguration("Task: collided", foreignType.configurationFactories[0])
        runManager.addConfiguration(foreign)

        // precondition: name-only lookup really does collide, otherwise this test proves nothing
        assertSame(
            "Expected findConfigurationByName to return the foreign configuration",
            foreign,
            runManager.findConfigurationByName("Task: collided"),
        )

        // used to throw ClassCastException because the foreign configuration was cast blindly
        val settings =
            TaskLineMarkerProvider.prepareConfiguration(project, "collided", "/tmp/Taskfile.yml")

        assertNotNull(
            "Should create its own configuration instead of reusing a foreign one",
            settings,
        )
        assertTrue(
            "Should return a Taskfile configuration",
            settings!!.configuration is TaskRunConfiguration,
        )
        assertNotSame(foreign, settings)
        assertEquals("Task: collided", settings.name)

        val runConfig = settings.configuration as TaskRunConfiguration
        assertEquals("collided", runConfig.task)
        assertEquals("/tmp/Taskfile.yml", runConfig.filename)

        // the unrelated configuration must survive untouched, alongside the new one
        assertTrue("Foreign configuration was removed", runManager.allSettings.contains(foreign))
        assertTrue(foreign.configuration !is TaskRunConfiguration)
        assertEquals(
            "Both configurations should coexist under the same name",
            2,
            runManager.allSettings.filter { it.name == "Task: collided" }.size,
        )
    }

    private fun taskSettingsNamed(name: String): List<RunnerAndConfigurationSettings> {
        val runManager = RunManagerImpl.getInstanceImpl(project)
        return runManager
            .getConfigurationSettingsList(
                ConfigurationTypeUtil.findConfigurationType(TaskRunConfigurationType::class.java)
            )
            .filter { it.name == name }
    }

    private fun findTaskKey(file: PsiFile, taskName: String): PsiElement? {
        return PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .find { it.keyText == taskName && it.parent?.parent is YAMLKeyValue }
            ?.key
    }

    private fun findKey(file: PsiFile, keyName: String): PsiElement? {
        return PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .find { it.keyText == keyName }
            ?.key
    }
}
