import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.PrepareTestTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("com.ncorti.ktfmt.gradle") version "0.27.0"
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "lechuck"

version = "2.0.1"

// bytecode stays at 17: it runs unchanged on every JBR from 2026.2 (the minimum
// supported version, see sinceBuild below) onward, and the toolchain keeps JDK 21/25
// APIs out of reach at compile time.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

kotlin { jvmToolchain(17) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// See
// - https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
// - https://jb.gg/intellij-platform-versions
dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdea, "2026.2") { useInstaller = false }
        bundledPlugin("org.jetbrains.plugins.yaml")
        testFramework(TestFrameworkType.Platform)
    }
    // gradle.properties sets kotlin.stdlib.default.dependency=false, so the stdlib
    // has to be requested explicitly to keep it bundled in the plugin distribution.
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.2")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            // EnvironmentVariablesComponent(Project), which TaskRunConfigurationEditor
            // builds its env-vars field with, only exists from 2026.2 (262) on.
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // Deprecated API usage is excluded: com.intellij.execution.util.ListTableWithButtons
        // references the deprecated java.util.Observable itself, on every IDE version,
        // and that usage lives in platform code we cannot change.
        failureLevel =
            listOf(
                VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
                VerifyPluginTask.FailureLevel.EXPERIMENTAL_API_USAGES,
                VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
                VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
                VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
                VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
                VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            )
        ides {
            current()
            latest { types.set(listOf(IntelliJPlatformType.IntellijIdea)) }
        }
    }
    publishing { token = System.getenv("ORG_GRADLE_PROJECT_intellijPublishToken") }
}

intellijPlatformTesting.runIde.register("runIdeLatest") {
    type = IntelliJPlatformType.IntellijIdea
    version = providers.gradleProperty("runIdeLatestVersion")
}

tasks.jar { from("LICENSE") }

// IPGP 2.x does not wire buildPlugin into assemble/build the way the 1.x plugin did.
tasks.named("build") { dependsOn("buildPlugin") }

ktfmt { kotlinLangStyle() }

// TODO: remove once https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/2070
// is fixed and a release picks it up.
//
// The unified IntelliJ IDEA distribution bundles plugins like Vue.js whose lib/modules/*.jar
// layout IPGP 2.18.1 doesn't parse correctly. BasePlatformTestCase loads every bundled plugin,
// so a test that touches a shared extension point (here, LSP server registration) drags in
// Vue.js and crashes on that bad classpath parse, even though the plugin itself is unrelated
// to this project. Disabling it in the test sandbox's own config -- the same file-based
// mechanism the platform already uses for user-disabled plugins -- sidesteps the parse instead
// of loading and failing on it. Written after prepareTest builds the sandbox, since prepareTest
// would otherwise overwrite it.
val disableBrokenBundledTestPlugins =
    tasks.register("disableBrokenBundledTestPlugins") {
        val prepareTest = tasks.named<PrepareTestTask>("prepareTest")
        dependsOn(prepareTest)
        val configDir = prepareTest.flatMap { it.sandboxConfigDirectory }
        outputs.dir(configDir)
        doLast {
            configDir
                .get()
                .asFile
                .resolve("disabled_plugins.txt")
                .writeText("org.jetbrains.plugins.vue\n")
        }
    }

tasks.named("test") { dependsOn(disableBrokenBundledTestPlugins) }

// Fixes applied to the interactive-run sandboxes (runIde, runIdeLatest) only -- the test
// sandbox above already has its own narrower fix for the one bug it actually hits.
//
// - com.intellij.platform.daemon (the "JetBrains OS Integration" plugin) fails a discovery
//   probe against this setup and logs a stack trace on every launch; JetBrains's own guidance
//   for that failure is to disable the plugin, the same way #2070 is worked around above.
// - org.jetbrains.plugins.vue hits that same #2070 lib/modules layout bug, just not (yet)
//   through a code path manual testing happens to exercise.
// - The native file watcher (fsnotifier) ships without its executable bit when the platform
//   downloads as a plain zip (useInstaller = false above) rather than a native installer, so
//   IntelliJ falls back to slower filesystem polling and warns about it on every launch.
fun fixRunSandbox(prepareSandboxTaskName: String, taskName: String) =
    tasks.register(taskName) {
        val prepareSandbox = tasks.named<PrepareSandboxTask>(prepareSandboxTaskName)
        dependsOn(prepareSandbox)
        val configDir = prepareSandbox.flatMap { it.sandboxConfigDirectory }
        val platformPath = prepareSandbox.map { it.platformPath }
        outputs.dir(configDir)
        doLast {
            configDir
                .get()
                .asFile
                .resolve("disabled_plugins.txt")
                .writeText("com.intellij.platform.daemon\norg.jetbrains.plugins.vue\n")
            platformPath
                .get()
                .resolve("bin")
                .toFile()
                .walkTopDown()
                .filter { it.name == "fsnotifier" || it.name == "fsnotifier.exe" }
                .forEach { it.setExecutable(true) }
        }
    }

val fixRunIdeSandbox = fixRunSandbox("prepareSandbox", "fixRunIdeSandbox")
val fixRunIdeLatestSandbox = fixRunSandbox("prepareSandbox_runIdeLatest", "fixRunIdeLatestSandbox")

tasks.named("runIde") { dependsOn(fixRunIdeSandbox) }

tasks.named("runIdeLatest") { dependsOn(fixRunIdeLatestSandbox) }
