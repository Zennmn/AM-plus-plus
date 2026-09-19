plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    android {
        namespace = "com.kyant.backdrop"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets.commonMain.dependencies {
        api("org.jetbrains.compose.foundation:foundation:1.12.0")
        api("org.jetbrains.compose.ui:ui:1.12.0")
        api("org.jetbrains.compose.ui:ui-graphics:1.12.0")
        api("io.github.kyant0:shapes:1.2.1")
        implementation("org.jetbrains:annotations:26.1.0")
    }
}
