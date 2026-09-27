plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 ships built-in Kotlin support; declaring the plugin here pins the Kotlin version.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
