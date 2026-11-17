plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
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
        intellijIdea("2026.3")
        // Compose runtime/foundation (androidx.*) as shipped in the rebased IDE
        bundledModule("intellij.libraries.compose.foundation.desktop")
        bundledModule("intellij.libraries.compose.runtime.desktop")
        bundledModule("intellij.libraries.compose.swing")
        bundledModule("intellij.libraries.skiko")
        // XML / HTML support
        bundledModule("intellij.xml.psi")
        bundledModule("intellij.xml.psi.impl")
        bundledModule("intellij.xml.parser")
        bundledModule("intellij.xml.analysis")
        bundledModule("intellij.xml.analysis.impl")
        // VCS ignore providers
        bundledModule("intellij.platform.vcs.core")
        bundledModule("intellij.platform.vcs.impl.shared")
        bundledModule("intellij.platform.vcs")
        // project extension functions (com.intellij.project.*)
        bundledModule("intellij.platform.projectModel.impl")
        // structure view / navbar
        bundledModule("intellij.platform.structureView")
        bundledModule("intellij.platform.structureView.impl")
        // platform images plugin (ImageFileTypeManager, SvgFileType, EditorImagePasteProvider)
        bundledModule("com.intellij.platform.images")
        // parseHostAndPath / readGitRemoteUrls (org.jetbrains.ide.*)
        bundledModule("intellij.platform.builtInServer.impl")
        bundledModule("intellij.platform.jewel.foundation")
        bundledModule("intellij.platform.jewel.ui")
        bundledModule("intellij.platform.jewel.ideLafBridge")
        bundledModule("intellij.platform.jewel.markdown.core")
        bundledModule("intellij.platform.jewel.markdown.ideLafBridgeStyling")
        bundledModule("intellij.platform.jewel.markdown.extensions.autolink")
        bundledModule("intellij.platform.jewel.markdown.extensions.gfmTables")
        bundledModule("intellij.platform.jewel.markdown.extensions.gfmAlerts")
        bundledModule("intellij.platform.jewel.markdown.extensions.gfmStrikethrough")
        bundledModule("intellij.platform.jewel.markdown.extensions.images")
        bundledModule("intellij.platform.compose")
        bundledModule("intellij.platform.rpc.lite")
        bundledModule("fleet.rpc")
        bundledModule("intellij.libraries.coil")
        bundledModule("intellij.textmate")
        bundledModule("intellij.textmate.core")
        bundledModule("intellij.textmate.common")
        // spellchecker (SpellcheckingStrategy/Tokenizer) — rebased ships it as a product module
        bundledModule("intellij.spellchecker")
        // The rebased platform bundles a JetBrains-internal variant of org.jetbrains:markdown
        // (BlockQuoteProvider has only a no-arg ctor). Compile against that exact jar instead of
        // a Maven version so the bytecode is binary-compatible by construction.
        implementation(files("libs/intellij.libraries.markdown.jar"))
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // upstream sources use Kotlin 2.4 name-based destructuring (val [a, b] = pair)
        freeCompilerArgs.add("-XXLanguage:+NameBasedDestructuring")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

intellijPlatform {
    buildSearchableOptions = false
}

tasks {
    jarSearchableOptions { enabled = false }
}
