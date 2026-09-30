// Throwaway diagnostics run in CI (GitHub's runners can reach YouTube / LRCLIB / iTunes; the
// dev sandbox can't). Not part of the app build.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "probe"
