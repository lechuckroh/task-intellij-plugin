import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareTestTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("com.ncorti.ktfmt.gradle") version "0.27.0"
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "lechuck"

version = "1.8.0"

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
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3")
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

// IPGP 2.x does not wire buildPlugin into assemble/build the way the 1.x plugin did,
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
