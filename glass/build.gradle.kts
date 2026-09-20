plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.amenhancer.glass"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    freeCompilerArgs.add("-Xlambdas=class")
} }
dependencies {
    api(project(":backdrop"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.3")
    testImplementation("junit:junit:4.13.2")
}
