import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
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
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
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
            <p>DocScribe 0.1.6: batched workspace checking, RubyMine 2026.1 – 2026.2 support.</p>
            <ul>
                <li>Workspace check now sends all Ruby files to the daemon in a single batch RPC call</li>
                <li>Requires docscribe gem &gt;= 1.5.2 for batch mode (falls back to CLI otherwise)</li>
                <li>Supports RubyMine 2026.1 and 2026.2</li>
                <li>Auto-generate YARD documentation for Ruby methods</li>
                <li>Check file / workspace diagnostics</li>
                <li>Safe and aggressive fix actions</li>
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
}
