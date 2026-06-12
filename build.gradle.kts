plugins {
    id("org.jetbrains.intellij.platform") version "2.5.0"
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.github.atishagrawal.apkwebhook"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Use locally installed Android Studio to skip the ~1 GB SDK download.
        // Override with -PandroidStudio.path=/custom/path if installed elsewhere.
        val androidStudioPath = providers.gradleProperty("androidStudio.path")
            .getOrElse("${System.getProperty("user.home")}/Downloads/android-studio")
        local(file(androidStudioPath))
        bundledPlugin("Git4Idea")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.android")
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "299.*"
        }
    }
}

tasks.shadowJar {
    relocate("okhttp3", "io.github.atishagrawal.apkwebhook.shaded.okhttp3")
    relocate("kotlinx.serialization", "io.github.atishagrawal.apkwebhook.shaded.kotlinx.serialization")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}
