plugins { alias(libs.plugins.android.library) }

android {
    namespace = "ai.argvid.gen0.gimbal"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
