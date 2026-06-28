plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debugtools.sample"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.debugtools.sample"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation(project(":debugtools-core"))
    implementation(project(":debugtools-network"))
    implementation(project(":debugtools-timeline"))
    implementation(project(":debugtools-general"))
    implementation(project(":debugtools-okhttp-capture"))
    implementation(project(":debugtools-perfmon"))
    implementation(project(":debugtools-audiomon"))
    implementation(project(":debugtools-startup"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Self-contained local server so the network-capture demo works offline (no httpbin/echo needed)
    implementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
