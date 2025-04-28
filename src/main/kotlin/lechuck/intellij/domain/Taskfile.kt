package lechuck.intellij.domain

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.io.InputStream
import java.nio.file.Paths

data class Taskfile(
    val includes: Map<String, Include> = emptyMap(),
    val tasks: Map<String, Task> = emptyMap()
) {
    /**
     * Retrieves a collection of task names defined in this Taskfile,
     * including tasks from included Taskfiles.
     *
     * @return A collection of task names, including task names from included Taskfiles.
     */
    fun getTaskNames(): Collection<String> {
        val taskNames = tasks.keys.toMutableList()
        includes.forEach { (key, include) ->
            val taskNamesWithNamespaces = include.taskfile.getTaskNames().map { "$key:$it" }
            taskNames.addAll(taskNamesWithNamespaces)
        }
        return taskNames
    }

    companion object {
        private val mapper = YAMLMapper().registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        /**
         * Loads a Taskfile object from the provided InputStream.
         *
         * @param inputStream The input stream containing the YAML representation of the Taskfile.
         * @return The deserialized Taskfile object.
         */
        fun load(inputStream: InputStream): Taskfile {
            val rootNode = mapper.readTree(inputStream)
            val tasksNode = rootNode.get("tasks")
            val tasks = mutableMapOf<String, Task>()
            tasksNode?.fields()?.forEach { (key, value) ->
                try {
                    val task = if (value.isTextual) {
                        Task()
                    } else {
                        mapper.convertValue(value, Task::class.java)
                    }
                    tasks[key] = task
                } catch (e: Exception) {
                    println("failed to parse task $key: ${e.message}")
                }
            }
            return Taskfile(tasks = tasks)
        }

        fun load(psiManager: PsiManager, file: PsiFile): Taskfile {
            file.virtualFile.inputStream.use { inputStream ->
                val baseDir = file.containingDirectory.virtualFile.path
                val rootNode = mapper.readTree(file.virtualFile.inputStream)
                val includesNode = rootNode.get("includes")
                val tasksNode = rootNode.get("tasks")
                val includes = mutableMapOf<String, Include>()
                val tasks = mutableMapOf<String, Task>()
                includesNode?.fields()?.forEach { (key, includeValue) ->
                    try {
                        includes[key] = loadInclude(psiManager, includeValue, baseDir) ?: return@forEach
                    } catch (e: Exception) {
                        println("failed to parse include $key: ${e.message}")
                    }
                }
                tasksNode?.fields()?.forEach { (key, value) ->
                    try {
                        val task = if (value.isTextual) {
                            Task()
                        } else {
                            mapper.convertValue(value, Task::class.java)
                        }
                        tasks[key] = task
                    } catch (e: Exception) {
                        println("failed to parse task $key: ${e.message}")
                    }
                }
                return Taskfile(includes, tasks)
            }
        }

        private fun loadInclude(psiManager: PsiManager, node: JsonNode, baseDir: String): Include? {
            val taskfilePath = if (node.isTextual) {
                node.asText()
            } else {
                node.get("taskfile").asText()
            }

            val file = findPsiFile(psiManager, baseDir, taskfilePath) ?: return null
            val taskfile = load(psiManager, file)

            return Include(
                taskfile = taskfile,
                flatten = node.get("flatten")?.asBoolean() ?: false,
                internal = node.get("internal")?.asBoolean() ?: false,
                aliases = node.get("aliases")?.map { it.asText() } ?: emptyList(),
            )
        }

        private fun findPsiFile(psiManager: PsiManager, baseDir: String, path: String): PsiFile? {
            val absolutePath = if (Paths.get(path).isAbsolute) {
                path
            } else {
                Paths.get(baseDir, path).normalize().toString()
            }

            val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return null
            return psiManager.findFile(vFile)
        }
    }
}