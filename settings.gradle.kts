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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "anonrode-player"
include(":app")
include(":core:model")
include(":core:database")
include(":core:datastore")
include(":core:media")
include(":core:ui")
include(":feature:player")
include(":feature:library")
