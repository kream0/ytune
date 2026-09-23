plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// CI passes the run number so every build installs over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Nothing's Glyph Matrix SDK is a closed-source AAR whose licence doesn't allow
// redistribution, so it isn't committed: it's fetched from Nothing's official repo at a
// pinned commit and checked against a known SHA-256.
val glyphSdk = file("libs/glyph-matrix-sdk-2.0.aar")
if (!glyphSdk.exists()) {
    val url = "https://raw.githubusercontent.com/Nothing-Developer-Programme/Glyph-Developer-Kit/" +
        "8ee807a9312a640b0d43051450924e3446bc1d78/sdk/glyph-matrix-sdk-2.0.aar"
    val bytes = java.net.URI(url).toURL().readBytes()
    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    check(sha == "329393019db5f0f987c6245855d13fa273d06756c68829ca0f6ae686ba336da1") {
        "Unexpected checksum for the Glyph SDK download ($sha)"
    }
    glyphSdk.parentFile.mkdirs()
    glyphSdk.writeBytes(bytes)
}

// Where the in-app updater looks for new builds (the rolling "latest" release).
val updateRepo = System.getenv("GITHUB_REPOSITORY") ?: "kream0/ytune"

// A private keystore can be supplied through env vars (see README / the CI workflow).
// Without one, builds are signed with the public dev key in /keystore so updates still
// install over each other.
val releaseKeystore = System.getenv("YTUNE_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "app.ytune"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ytune"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        create("sideload") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("YTUNE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("YTUNE_KEY_ALIAS")
                keyPassword = System.getenv("YTUNE_KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("keystore/ytune-dev.jks")
                storePassword = "ytune-dev"
                keyAlias = "ytune"
                keyPassword = "ytune-dev"
            }
        }
    }

    buildTypes {
        release {
            // Extractor + Rhino rely on reflection; shrinking isn't worth the risk for a sideloaded app.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/README.md",
                "META-INF/CHANGES",
                "META-INF/COPYRIGHT",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    implementation(libs.newpipe.extractor)
    implementation(files(glyphSdk))
}
