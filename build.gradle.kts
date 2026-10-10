// Root build file. Plugins are declared per module via gradle/libs.versions.toml.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
