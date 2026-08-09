package lechuck.intellij

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
