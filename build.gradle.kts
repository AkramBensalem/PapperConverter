plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.9.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
}

group = "me.akram.bensalem"
version = "0.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2024.3.3")
    }

    implementation("com.squareup.okhttp3:okhttp:4.12.0") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-client-core:3.3.0") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-client-okhttp:3.3.0") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-client-content-negotiation:3.3.0") {
        exclude(group = "org.slf4j")
    }
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.0") {
        exclude(group = "org.slf4j")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // GraalPy for offline PDF processing with MarkItDown
    implementation("org.graalvm.polyglot:polyglot:24.0.0")
    implementation("org.graalvm.polyglot:python:24.0.0")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
        }
        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

tasks {
    withType<JavaCompile> {
        options.release.set(17)
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    test {
        useJUnitPlatform()
    }
    buildSearchableOptions {
        enabled = false
    }
}
