plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.ww97951807"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2")
        bundledModule("intellij.platform.jewel.foundation")
        bundledModule("intellij.platform.jewel.ui")
        bundledModule("intellij.platform.jewel.ideLafBridge")
        bundledModule("intellij.platform.jewel.markdown.core")
        bundledModule("intellij.platform.compose")
        // The rebased platform bundles a JetBrains-internal variant of org.jetbrains:markdown
        // (BlockQuoteProvider has only a no-arg ctor). Compile against that exact jar instead of
        // a Maven version so the bytecode is binary-compatible by construction.
        implementation(files("libs/intellij.libraries.markdown.jar"))
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
}

tasks {
    jarSearchableOptions { enabled = false }
}
