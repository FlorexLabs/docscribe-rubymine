import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("com.diffplug.spotless") version "8.6.0"
    id("dev.detekt") version "2.0.0-alpha.4"
    id("org.jetbrains.dokka") version "2.0.0"
}

group = "com.florexlabs"
version = providers.gradleProperty("pluginVersion").get()

val mcpEnabled = findProperty("mcpTest") == "true"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
    }
}

dependencies {
    intellijPlatform {
        rubymine(providers.gradleProperty("platformVersion").get())
        bundledPlugin("org.jetbrains.plugins.ruby")
        if (mcpEnabled) bundledPlugin("com.intellij.mcpServer")
        testFramework(TestFrameworkType.Platform)
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.0")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

sourceSets {
    if (mcpEnabled) {
        named("main") {
            kotlin.srcDir("src/mcp/kotlin")
            resources.srcDir("src/mcp/resources")
        }
    }
    test {
        kotlin {
            exclude("**/mcp/**")
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.florexlabs.docscribe"
        name = "DocScribe"
        version = providers.gradleProperty("pluginVersion").get()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }

        changeNotes =
            """
            <p>DocScribe 0.2.0: RubyMine 2026.3 support.</p>
            <ul>
                <li>Compatible with RubyMine 2026.3 (build <code>263.*</code>)</li>
                <li>Supported range is now 2026.1 – 2026.3</li>
                <li><code>FileEditorManager</code> is now resolved via the project service, fixing a 2026.1/2026.2 runtime failure</li>
            </ul>
            """.trimIndent()
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    pluginVerification {
        ides {
            create("RM", "2026.1") {} // oldest supported line
            create("RM", "2026.2.4") {} // latest 2026.2 patch
            create("RM", "2026.3") {} // 2026.3 GA
        }
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_TOKEN")
    }
}

spotless {
    kotlin {
        ktlint()
        target("src/**/*.kt")
    }
    kotlinGradle {
        ktlint()
        target("*.kts")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("config/detekt/detekt.yml"))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask> {
        val localGemPath =
            project.findProperty("docscribe.local.gem.path")?.toString()
                ?: System.getenv("DOCSCRIBE_LOCAL_GEM_PATH")
        if (localGemPath != null) {
            jvmArgs(listOf("-Ddocscribe.local.gem.path=$localGemPath"))
        }
    }
    withType<Test> {
        // MCP toolset is heavy (mcpServer 262+) and not needed for unit tests — exclude to keep CI 7m
        exclude("**/mcp/**")
    }
    register("enableMcp") {
        description = "Copy MCP toolset from src/mcp to src/main for local testing via JetBrains MCP"
        group = "docscribe"
        notCompatibleWithConfigurationCache("uses project.copy")
        doLast {
            copy {
                from("src/mcp/kotlin/com/florexlabs/docscribe/mcp")
                into("src/main/kotlin/com/florexlabs/docscribe/mcp")
            }
            copy {
                from("src/mcp/resources/META-INF/withMcpServer.xml")
                into("src/main/resources/META-INF")
            }
            setMcpDepends(inject = true)
            println("✅ MCP enabled — run ./gradlew buildPlugin to include 6 tools in zip")
        }
    }
    register("disableMcp") {
        description = "Remove MCP toolset from src/main before push (keep CI 7m)"
        group = "docscribe"
        notCompatibleWithConfigurationCache("uses project.delete")
        doLast {
            delete("src/main/kotlin/com/florexlabs/docscribe/mcp")
            delete("src/main/resources/META-INF/withMcpServer.xml")
            setMcpDepends(inject = false)
            println("✅ MCP disabled — CI will not see MCP files")
        }
    }
}

// The optional com.intellij.mcpServer <depends> must match withMcpServer.xml being present:
// a config-file pointing at a missing file is a verifier "plugin configuration defect".
fun setMcpDepends(inject: Boolean) {
    val marker = "<!-- MCP_DEPENDS -->"
    val depends = "$marker <depends optional=\"true\" config-file=\"withMcpServer.xml\">com.intellij.mcpServer</depends>"
    val file = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml").asFile
    val pattern =
        Regex(
            Regex.escape(marker) +
                "( <depends optional=\"true\" config-file=\"withMcpServer\\.xml\">com\\.intellij\\.mcpServer</depends>)?",
        )
    file.writeText(file.readText().replace(pattern, if (inject) depends else marker))
}
