plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "ai.argvid.gen0"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.argvid.gen0.camera"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":adapter:capture"))
    implementation(project(":adapter:gimbal"))
    implementation(project(":core:domain"))
    implementation(project(":data:media"))
    implementation(project(":feature:session"))
    implementation(project(":feature:today"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
