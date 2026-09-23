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
        // NewPipeExtractor and its nanojson fork are published through JitPack
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "YTune"
include(":app")
