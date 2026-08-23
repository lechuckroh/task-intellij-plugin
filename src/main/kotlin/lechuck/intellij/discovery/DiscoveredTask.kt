package lechuck.intellij.discovery

/**
 * One task as reported by the [TaskDiscovery] seam, regardless of which backend produced it.
 * [TaskYamlDiscovery] only ever fills in [name]; the rest stay at their defaults since a single
 * Taskfile's own YAML doesn't carry the location task itself would resolve `includes:` to.
 */
class DiscoveredTask {
    var name: String = ""
    var desc: String = ""
    var summary: String = ""
    var aliases: List<String> = emptyList()
    var taskfilePath: String = ""
    var line: Int? = null

    /**
     * Whether this task is marked `internal: true` -- excluded from [TaskCliDiscovery]'s own output
     * regardless of any flag passed to `task`, so the only way to find one at all is
     * [TaskYamlDiscovery.discoverInternalOnly] scanning a single Taskfile's own YAML directly.
     */
    var isInternal: Boolean = false
}
