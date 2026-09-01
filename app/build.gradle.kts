import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull
        ?: releaseSigningProperties.getProperty(propertyName)

val releaseStoreFilePath = releaseSigningValue("AMPP_RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = releaseSigningValue("AMPP_RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = releaseSigningValue("AMPP_RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = releaseSigningValue("AMPP_RELEASE_KEY_PASSWORD", "keyPassword")
val releaseSigningAvailable = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "dev.amenhancer.module"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.amenhancer.module"
        minSdk = 26
        targetSdk = 37
        versionCode = 104
        versionName = "1.5.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFilePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("io.github.libxposed:service:102.0.0")
    testCompileOnly("io.github.libxposed:service:102.0.0")
    implementation("com.github.Dimezis:BlurView:version-3.2.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // HLE's exact Apple Music profile resolver uses DexKit only as a
    // compatibility fallback when a profiled class/member is absent.
    implementation("org.luckypray:dexkit:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    // org.json is part of the Android runtime but not of the local JVM; the
    // test-only copy keeps the NetEase response parsing unit-testable.
    testImplementation("org.json:json:20240303")
}
