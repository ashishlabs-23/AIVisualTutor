// Root build file. Plugins are declared here (with apply false) so that
// the composeApp module can apply them without redeclaring versions.
plugins {
    kotlin("jvm") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
