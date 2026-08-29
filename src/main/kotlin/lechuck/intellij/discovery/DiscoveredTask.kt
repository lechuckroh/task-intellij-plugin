package lechuck.intellij.discovery

/**
 * One task as reported by the [TaskDiscovery] seam, regardless of which backend produced it.
 * [TaskYamlDiscovery] fills in everything it can read from the YAML itself -- [name], [desc],
 * [summary], [aliases], [taskfilePath] and [isInternal] -- but never [line], and its names cover
 * only the includes it can follow from the files alone (see [TaskDiscovery] for which those are).
 */
class DiscoveredTask {
    var name: String = ""
    var desc: String = ""
    var summary: String = ""
    var aliases: List<String> = emptyList()
    var taskfilePath: String = ""
    var line: Int? = null

    /**
     * Whether this task is marked `internal: true`, either on the task or on the `includes:` entry
     * that brought it in. Excluded from [TaskCliDiscovery]'s own output regardless of any flag
     * passed to `task` -- and not runnable either way -- so
     * [TaskYamlDiscovery.discoverInternalOnly] is the only way to see one at all.
     */
    var isInternal: Boolean = false
}
