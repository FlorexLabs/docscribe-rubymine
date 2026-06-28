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
            <p>Initial release of DocScribe for RubyMine.</p>
            <ul>
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
        val localGemPath = project.findProperty("docscribe.local.gem.path")?.toString()
        if (localGemPath != null) {
            jvmArgs(listOf("-Ddocscribe.local.gem.path=$localGemPath"))
        }
    }
}
