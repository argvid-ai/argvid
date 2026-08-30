pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Gen0Camera"
include(
    ":app",
    ":core:domain",
    ":adapter:gimbal",
    ":adapter:capture",
    ":data:media",
    ":feature:session",
    ":feature:today",
    ":testing:fixtures",
)
