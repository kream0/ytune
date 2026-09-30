plugins {
    kotlin("jvm") version "2.1.0"
    application
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:13a655fe53e0c3065f88725fc1fb594c3ede0169")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
sourceSets {
    main {
        kotlin.srcDirs(
            "src/main/kotlin",
            "../app/src/main/java/app/ytune/lyrics",
            "../app/src/main/java/app/ytune/album",
        )
        kotlin.srcDir("../app/src/main/java/app/ytune/yt")
    }
}
kotlin.sourceSets["main"].kotlin.exclude(
    "**/LyricsRepo.kt", "**/LyricsView.kt", "**/AlbumFinder.kt", "**/YouTube.kt",
)
application { mainClass.set("MainKt") }
