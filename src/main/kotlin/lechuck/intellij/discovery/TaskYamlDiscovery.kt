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
 * Why an include could not be followed. Never silently dropped: an include this parser cannot
 * resolve is reported so a caller can say so, rather than showing a task list that is quietly
 * missing half a project (see [TaskDiscovery]'s role split).
 */
internal enum class UnresolvedIncludeReason {
    /** Points at a URL -- only the CLI downloads, verifies and caches those. */
    REMOTE,
    /** Path contains a template, so which file it names cannot be known without running Task. */
    DYNAMIC,
    /** Resolves to a path that does not exist, and the include is not `optional: true`. */
    MISSING,
}

internal data class UnresolvedInclude(
    val namespace: String,
    val reason: UnresolvedIncludeReason,
    val detail: String,
)

/** Tasks found by walking a Taskfile and its local `includes:`, plus what could not be walked. */
internal data class YamlDiscoveryResult(
    val tasks: List<DiscoveredTask>,
    val unresolvedIncludes: List<UnresolvedInclude>,
    /**
     * Every Taskfile that contributed, the root included. What
     * [lechuck.intellij.explorer .TaskDiscoveryCache] keys its graph invalidation on: editing an
     * included file has to invalidate the entry cached under the file that includes it, and an
     * included file can have any name at all (`taskfile:` takes an arbitrary path), so a name
     * filter cannot find it.
     */
    val sourceFiles: Set<String>,
)

/**
 * Discovers tasks by parsing Taskfile YAML directly, following `includes:` that can be resolved
 * from the files alone.
 *
 * This is the *structure* half of the split described on [TaskDiscovery]: it knows what a Taskfile
 * says, including the `internal:` tasks the CLI never reports and the tasks in included files, and
 * it can answer while a file is still being typed. It deliberately does not try to be the CLI --
 * three kinds of include are reported as unresolved rather than guessed at:
 *
 * - **remote** (`https://…`, `git@…`): Task downloads these itself with a trust prompt, host
 *   allowlist, checksum verification and a cache. Fetching them here would mean an IDE reaching out
 *   to the network in the background, on a file the user only opened -- and reimplementing that
 *   whole policy besides. Note a `checksum:` does *not* make an include remote: Task verifies local
 *   ones too.
 * - **dynamic** (`{{.VAR}}` in the path): which file is included can depend on a variable, and a
 *   variable can be defined by `sh:` -- i.e. answering would mean running a shell.
 * - **missing** and not `optional: true`.
 */
internal object TaskYamlDiscovery {
    private val LOG = Logger.getInstance(TaskYamlDiscovery::class.java)
    private val mapper =
        ObjectMapper(YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /**
     * The names Task looks for when an include names a directory rather than a file. All 8 of the
     * casings Task recognizes, `.dist` included -- a directory holding only `Taskfile.dist.yml` is
     * a real include, and treating it as missing loses every task in it. `Taskfile.yml` first,
     * matching which one Task picks when several are present.
     */
    private val DIRECTORY_INCLUDE_NAMES =
        listOf(
            "Taskfile.yml",
            "Taskfile.yaml",
            "Taskfile.dist.yml",
            "Taskfile.dist.yaml",
            "taskfile.yml",
            "taskfile.yaml",
            "taskfile.dist.yml",
            "taskfile.dist.yaml",
        )

    /** Guards against a pathological include chain even when no single file repeats. */
    private const val MAX_DEPTH = 32

    fun discover(project: Project, file: VirtualFile): List<DiscoveredTask> =
        discoverDetailed(project, file).tasks

    /** As [discover], but also reporting unresolved includes and the files that contributed. */
    fun discoverDetailed(project: Project, file: VirtualFile): YamlDiscoveryResult =
        Walk(project, includeInternal = false).run(file)

    /**
     * The `internal: true` tasks, which [TaskCliDiscovery] never reports regardless of flags
     * (verified against real `task` output) -- so parsing is the only way to find one at all.
     * Follows `includes:` the same way [discover] does, since an internal task in an included file
     * is just as invisible to the CLI as one in the root Taskfile.
     */
    fun discoverInternalOnly(project: Project, file: VirtualFile): List<DiscoveredTask> =
        Walk(project, includeInternal = true).run(file).tasks.filter { it.isInternal }

    /**
     * Every Taskfile reachable from [file] through local `includes:`, [file] itself included.
     *
     * Exists for the CLI path, which cannot work this out on its own: the CLI reports where each
     * *task* was defined, so a Taskfile that only re-includes others -- or holds only `internal:`
     * tasks -- never appears in its output, even though editing it changes what its includer
     * offers.
     *
     * Deliberately *not* the task walk with its results thrown away. A set of paths does not depend
     * on the namespace a file was reached under, so this one memoizes per file and visits each at
     * most once. The task walk cannot: the same file reached under two namespaces contributes twice
     * (see [Walk]), which on repeated fan-in costs exponentially many visits -- fine for a walk
     * that only happens when the CLI is unavailable, not for one that would now run on every
     * successful discovery.
     */
    fun sourceFilesOf(project: Project, file: VirtualFile): Set<String> {
        val seen = mutableSetOf<String>()

        fun visit(from: VirtualFile, depth: Int) {
            if (depth > MAX_DEPTH || !seen.add(from.path)) return
            val taskfile = readText(project, from)?.let { parse(it) } ?: return
            taskfile.includes.orEmpty().values.forEach { raw ->
                val include = Include.of(raw) ?: return@forEach
                if (include.isRemote || include.isDynamic) return@forEach
                resolve(from, include.path)?.let { visit(it, depth + 1) }
            }
        }

        visit(file, 0)
        return seen
    }

    /** Single file, no includes: the path has no directory to resolve them against. */
    internal fun discover(yaml: String): List<DiscoveredTask> =
        parse(yaml)?.let { tasksOf(it, prefix = "", forceInternal = false) } ?: emptyList()

    /** Single file, no includes -- see [discover]. */
    internal fun discoverInternalOnly(yaml: String): List<DiscoveredTask> =
        discover(yaml).filter { it.isInternal }

    private fun parse(yaml: String): YamlTaskfile? =
        try {
            mapper.readValue(yaml, YamlTaskfile::class.java)
        } catch (e: Exception) {
            LOG.warn("Failed to parse Taskfile YAML", e)
            null
        }

    private fun tasksOf(
        taskfile: YamlTaskfile,
        prefix: String,
        forceInternal: Boolean,
    ): List<DiscoveredTask> =
        taskfile.tasks.orEmpty().map { (name, value) ->
            val definition = value as? Map<*, *>
            DiscoveredTask().also {
                it.name = prefix + name
                it.desc = definition?.get("desc") as? String ?: ""
                it.summary = definition?.get("summary") as? String ?: ""
                // Prefixed like the name is: Task resolves an included task's alias under the
                // namespace too, and a bare alias errors with "does not exist".
                it.aliases =
                    (definition?.get("aliases") as? List<*>)
                        .orEmpty()
                        .filterIsInstance<String>()
                        .map { alias -> prefix + alias }
                // `internal: true` on the include itself makes every task it brings in uncallable,
                // exactly as marking each of them internal would.
                it.isInternal = forceInternal || definition?.get("internal") == true
            }
        }

    /**
     * One traversal, tracking the chain of files it is currently inside so a cycle stops at the
     * repeat.
     */
    private class Walk(private val project: Project, private val includeInternal: Boolean) {
        // The chain currently being descended, NOT every file already seen. A file included from
        // two different places (a "diamond": `a` and `b` both include `lib`) is legitimately
        // reachable twice and contributes its tasks under both namespaces -- Task lists `a:shared`
        // and `b:shared`. A global visited set would keep only whichever branch got there first and
        // drop the other silently, which is the exact failure this parser exists to avoid. Task's
        // own cycle check is ancestry-based too ("include cycle detected between X <--> X").
        private val ancestors = mutableSetOf<String>()
        private val parsed = mutableSetOf<String>()
        private val unresolved = mutableListOf<UnresolvedInclude>()

        fun run(file: VirtualFile): YamlDiscoveryResult {
            val tasks = walk(file, prefix = "", forceInternal = false, depth = 0)
            return YamlDiscoveryResult(tasks, unresolved, parsed.toSet())
        }

        private fun walk(
            file: VirtualFile,
            prefix: String,
            forceInternal: Boolean,
            depth: Int,
        ): List<DiscoveredTask> {
            if (depth > MAX_DEPTH || file.path in ancestors) return emptyList()
            ancestors.add(file.path)
            try {
                return walkInside(file, prefix, forceInternal, depth)
            } finally {
                ancestors.remove(file.path)
            }
        }

        private fun walkInside(
            file: VirtualFile,
            prefix: String,
            forceInternal: Boolean,
            depth: Int,
        ): List<DiscoveredTask> {
            parsed.add(file.path)
            val taskfile = readText(project, file)?.let { parse(it) } ?: return emptyList()

            val own =
                tasksOf(taskfile, prefix, forceInternal)
                    .filter { includeInternal || !it.isInternal }
                    .onEach { it.taskfilePath = file.path }

            val included =
                taskfile.includes.orEmpty().flatMap { (namespace, raw) ->
                    val include =
                        Include.of(raw)
                            ?: run {
                                // No `taskfile:` to follow -- most often a `dir:`-only entry. Task
                                // rejects the whole Taskfile in that case, so inventing tasks for
                                // it would offer names nothing can run.
                                unresolved +=
                                    UnresolvedInclude(
                                        namespace,
                                        UnresolvedIncludeReason.MISSING,
                                        "no taskfile: to follow",
                                    )
                                return@flatMap emptyList()
                            }
                    when {
                        include.isRemote -> {
                            unresolved +=
                                UnresolvedInclude(
                                    namespace,
                                    UnresolvedIncludeReason.REMOTE,
                                    include.path,
                                )
                            emptyList()
                        }
                        include.isDynamic -> {
                            unresolved +=
                                UnresolvedInclude(
                                    namespace,
                                    UnresolvedIncludeReason.DYNAMIC,
                                    include.path,
                                )
                            emptyList()
                        }
                        else -> follow(file, namespace, include, prefix, forceInternal, depth)
                    }
                }

            return own + included
        }

        private fun follow(
            from: VirtualFile,
            namespace: String,
            include: Include,
            prefix: String,
            forceInternal: Boolean,
            depth: Int,
        ): List<DiscoveredTask> {
            val target = resolve(from, include.path)
            if (target == null) {
                // `optional: true` says a missing file is expected, so it is not worth reporting.
                if (!include.optional) {
                    unresolved +=
                        UnresolvedInclude(namespace, UnresolvedIncludeReason.MISSING, include.path)
                }
                return emptyList()
            }

            // `flatten: true` brings the included tasks in under no namespace at all.
            val childPrefix = if (include.flatten) prefix else "$prefix$namespace:"
            val found = walk(target, childPrefix, forceInternal || include.internal, depth + 1)
            return found.filterNot { include.isExcluded(it.name, childPrefix) }
        }
    }

    /**
     * Relative to the including file's own directory, which is how Task resolves an include -- but
     * absolute and `~`-prefixed paths are accepted too, since Task takes those as written and
     * `findFileByRelativePath` would call them missing.
     *
     * Resolved through the including file's own file system rather than LocalFileSystem: an include
     * is always reached from whatever VFS the Taskfile itself lives in.
     */
    private fun resolve(from: VirtualFile, path: String): VirtualFile? {
        val expanded = expandHome(path)
        val target =
            if (expanded.startsWith("/")) from.fileSystem.findFileByPath(expanded)
            else from.parent?.findFileByRelativePath(expanded)
        if (target == null || !target.isDirectory) return target
        return DIRECTORY_INCLUDE_NAMES.firstNotNullOfOrNull { target.findChild(it) }
    }

    /**
     * `~/x` means the home directory to Task, as it does to a shell. Split out as a pure function
     * because the alternative -- proving it through the VFS -- needs a fixture file at a real home
     * path, and the relative reading it must not fall back to only differs when a directory
     * literally named `~` exists.
     */
    internal fun expandHome(path: String): String =
        if (path.startsWith("~/")) System.getProperty("user.home") + path.removePrefix("~")
        else path

    // PSI rather than the bytes on disk, so a task typed into the editor but not saved yet is still
    // found -- the whole reason this parser can answer questions the CLI cannot.
    private fun readText(project: Project, file: VirtualFile): String? =
        ReadAction.computeBlocking<String?, RuntimeException> {
            PsiManager.getInstance(project).findFile(file)?.text
        }

    /** One `includes:` entry, in either of the two forms Task accepts (a string, or a map). */
    private class Include(
        val path: String,
        val optional: Boolean,
        val flatten: Boolean,
        val internal: Boolean,
        private val excludePatterns: List<String>,
    ) {
        /**
         * Only the path decides this. A `checksum:` does *not*: Task verifies the checksum of a
         * local include just as happily as a downloaded one, so treating it as a remote marker
         * would drop every task of a pinned local include.
         */
        val isRemote: Boolean
            get() = path.contains("://") || path.startsWith("git@")

        val isDynamic: Boolean
            get() = path.contains("{{")

        /** `excludes:` takes plain task names, or a namespace pattern ending in `:*`. */
        fun isExcluded(taskName: String, prefix: String): Boolean {
            val bare = taskName.removePrefix(prefix)
            return excludePatterns.any { pattern ->
                if (pattern.endsWith(":*")) bare.startsWith(pattern.removeSuffix("*"))
                else bare == pattern
            }
        }

        companion object {
            /**
             * Only `taskfile:` names a file. `dir:` is the working directory the included tasks
             * *run* in, not where they are defined -- reading it as a path would invent task names
             * that cannot be run. An entry with `dir:` and no `taskfile:` is not something this
             * parser can follow (Task itself errors on it), so it becomes null and is reported.
             */
            fun of(raw: Any?): Include? =
                when (raw) {
                    is String -> Include(raw, false, false, false, emptyList())
                    is Map<*, *> ->
                        (raw["taskfile"] as? String)?.let { path ->
                            Include(
                                path,
                                optional = raw["optional"] == true,
                                flatten = raw["flatten"] == true,
                                internal = raw["internal"] == true,
                                excludePatterns =
                                    (raw["excludes"] as? List<*>)
                                        .orEmpty()
                                        .filterIsInstance<String>(),
                            )
                        }
                    else -> null
                }
        }
    }

    private class YamlTaskfile {
        var tasks: Map<String, Any>? = null
        var includes: Map<String, Any>? = null
    }
}
