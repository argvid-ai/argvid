plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":testing:fixtures"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
