pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "F1-Real-Debrid"

include(":core")
// Pass -PcoreOnly to build/test only the pure-JVM module (no Android SDK needed).
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
