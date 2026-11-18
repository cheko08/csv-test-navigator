import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.csvnav"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Only use stdlib APIs that the oldest supported IDE (2024.3) ships.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
}

dependencies {
    intellijPlatform {
        // Builds against 2024.3; the plugin has no upper bound so it also loads in newer IDEs.
        // `-PlocalIde=<IDE install dir>` tests/runs against an installed IDE instead (with its CSV support).
        val localIde = providers.gradleProperty("localIde").orNull
        if (localIde != null) {
            local(localIde)
            bundledPlugin("com.intellij.grid")
        } else {
            intellijIdeaCommunity("2024.3.5")
        }
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}
