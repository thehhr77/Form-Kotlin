plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.thehhr.form.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.thehhr.form.nativeapp"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    sourceSets["main"].assets.srcDir("assets-form")
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

val syncFormAssets by tasks.registering {
    val webRoot = rootProject.layout.projectDirectory.dir("../Form - Web/www")
    inputs.file(webRoot.file("app.js"))
    inputs.dir(webRoot.dir("images"))
    inputs.dir(webRoot.dir("videos"))
    val output = layout.projectDirectory.dir("assets-form")
    outputs.dir(output)
    doLast {
        val destination = output.asFile
        destination.mkdirs()
        val firstLine = webRoot.file("app.js").asFile.bufferedReader().use { it.readLine() }
        require(firstLine.startsWith("const EXERCISES="))
        destination.resolve("exercises.json").writeText(firstLine.removePrefix("const EXERCISES=").trim().removeSuffix(";"))
        copy {
            from(webRoot) { include("images/**", "videos/**") }
            into(destination)
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.collection:collection:1.4.4")
    androidTestImplementation("androidx.collection:collection:1.4.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
