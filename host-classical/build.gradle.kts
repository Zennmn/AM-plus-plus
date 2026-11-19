plugins { id("com.android.library") }
android {
    namespace = "dev.amenhancer.host.classical"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core"))
    api(project(":host-api"))
    implementation(project(":hook-runtime"))
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("io.github.libxposed:service:102.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testRuntimeOnly("io.github.libxposed:api:102.0.0")
}
