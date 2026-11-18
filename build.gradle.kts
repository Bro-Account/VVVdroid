// Root build file: declares the plugin versions used by the modules.
// AGP 9.x has built-in Kotlin support (no more org.jetbrains.kotlin.android);
// the Compose compiler plugin version must match the Kotlin compiler that
// AGP's built-in Kotlin uses (9.5.x -> Kotlin 2.2.10).
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
