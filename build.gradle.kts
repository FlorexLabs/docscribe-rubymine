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

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
    }
}

dependencies {
    intellijPlatform {
        rubymine("2026.1")
        bundledPlugin("org.jetbrains.plugins.ruby")
        bundledPlugin("com.intellij.mcpServer")
        testFramework(TestFrameworkType.Platform)
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.0")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

sourceSets {
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
            <p>DocScribe 0.1.7: RBS-aware inspections, Doctor RBS status, MCP API and i18n.</p>
            <ul>
                <li>RBS-aware check / safe fix / aggressive fix / workspace batch via auto-detect <code>sig/*.rbs</code> or <code>rbs</code> gem</li>
                <li>Doctor shows <code>sig/</code>, <code>rbs</code> gem and <code>rbs.enabled</code></li>
                <li>Annotator cache invalidates on RBS changes</li>
                <li>Update Types via daemon with CLI fallback for old gems</li>
                <li>MCP toolset for API testing (6 tools) and English/Russian localizations</li>
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
            create("RM", "2026.2") {} // 2026.2 GA
            create("RM", "2026.2.1") {} // latest 2026.2 patch
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
            println("✅ MCP disabled — CI will not see MCP files")
        }
    }
}
