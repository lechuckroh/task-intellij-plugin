package lechuck.intellij.discovery

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

/**
 * Discovers tasks by parsing a single Taskfile's own YAML directly -- used only when the `task`
 * binary itself could not be launched (see [TaskCliDiscovery]). Unlike the CLI, this does not
 * resolve `includes:`: a task defined in an included file will not show up here.
 *
 * Reads [file] through its PSI rather than its bytes on disk, so a task typed into the editor but
 * not saved yet still shows up here -- there is no `includes:`-correctness to trade it away for
 * when the CLI isn't in play anyway.
 */
internal object TaskYamlDiscovery {
    private val LOG = Logger.getInstance(TaskYamlDiscovery::class.java)
    private val mapper =
        ObjectMapper(YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun discover(project: Project, file: VirtualFile): List<DiscoveredTask> {
        val text =
            ReadAction.computeBlocking<String?, RuntimeException> {
                PsiManager.getInstance(project).findFile(file)?.text
            }
        return text?.let { discover(it) } ?: emptyList()
    }

    internal fun discover(yaml: String): List<DiscoveredTask> =
        try {
            val taskfile = mapper.readValue(yaml, YamlTaskfile::class.java)
            taskfile.tasks?.keys.orEmpty().map { name -> DiscoveredTask().also { it.name = name } }
        } catch (e: Exception) {
            LOG.warn("Failed to parse Taskfile YAML", e)
            emptyList()
        }

    /**
     * Finds only the `internal: true` tasks defined directly in [file]'s own YAML -- the sole way
     * to find one at all, since [TaskCliDiscovery] never reports them regardless of flags (verified
     * empirically against real `task` output). Does not resolve `includes:`, same as [discover]: an
     * internal task defined in an included file will not show up here.
     */
    fun discoverInternalOnly(project: Project, file: VirtualFile): List<DiscoveredTask> {
        val text =
            ReadAction.computeBlocking<String?, RuntimeException> {
                PsiManager.getInstance(project).findFile(file)?.text
            }
        return text?.let { discoverInternalOnly(it) }?.onEach { it.taskfilePath = file.path }
            ?: emptyList()
    }

    internal fun discoverInternalOnly(yaml: String): List<DiscoveredTask> =
        try {
            val taskfile = mapper.readValue(yaml, YamlTaskfile::class.java)
            taskfile.tasks.orEmpty().mapNotNull { (name, value) ->
                val definition = value as? Map<*, *> ?: return@mapNotNull null
                if (definition["internal"] != true) return@mapNotNull null
                DiscoveredTask().also {
                    it.name = name
                    it.isInternal = true
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to parse Taskfile YAML", e)
            emptyList()
        }

    private class YamlTaskfile {
        var tasks: Map<String, Any>? = null
    }
}
