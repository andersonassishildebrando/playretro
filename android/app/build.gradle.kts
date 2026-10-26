plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The C++ application, resources and bundled theme live at the repository root.
val repoRoot = rootProject.projectDir.parentFile
val bundledTheme = "linear"
val playRetroVersionCode = 6
val playRetroVersionName = "0.1.0-beta"

android {
    namespace = "org.play_retro.frontend"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.play_retro.frontend"
        minSdk = 29
        targetSdk = 36
        versionCode = playRetroVersionCode
        versionName = playRetroVersionName

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_VERSION_CODE=$playRetroVersionCode",
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                // Service credentials are read from local, untracked Gradle properties
                // (e.g. ~/.gradle/gradle.properties); see PLAYRETRO.md.
                listOf("SCREENSCRAPER_DEV_ID", "SCREENSCRAPER_DEV_PASSWORD").forEach { key ->
                    (project.findProperty("playretro.$key") as String?)?.let {
                        arguments += "-D$key=$it"
                    }
                }
                targets += listOf("main", "es-pdf-convert")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("$repoRoot/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/main/kotlin")
            // SDL's Java glue, copied from the SDL2 sources by android/deps/build-deps.sh.
            java.srcDir("$repoRoot/android/sdl2-java")
            // Prebuilt dependencies from android/deps/build-deps.sh.
            jniLibs.srcDir("$repoRoot/android/libs")
            // Resources are read through the AssetManager with paths relative to resources/.
            assets.srcDir("$repoRoot/resources")
            assets.srcDir(layout.buildDirectory.dir("generated/themeAssets").get().asFile)
            res.srcDir(layout.buildDirectory.dir("generated/stringRes").get().asFile)
        }
    }

    // The release key lives outside the repository; its location and passwords come from local,
    // untracked Gradle properties (e.g. ~/.gradle/gradle.properties). Without them the release
    // APK is left unsigned.
    val signingProps = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { project.findProperty("playretro.signing.$it") as String? }
    if (signingProps.values.all { it != null }) {
        signingConfigs {
            create("release") {
                storeFile = file(signingProps.getValue("storeFile")!!)
                storePassword = signingProps.getValue("storePassword")
                keyAlias = signingProps.getValue("keyAlias")
                keyPassword = signingProps.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // Libraries are memory-mapped directly from the APK.
            useLegacyPackaging = false
        }
        // Static archives and unused HarfBuzz modules from the dependency build aren't packaged.
        jniLibs.excludes += listOf(
            "**/*.a",
            "**/libharfbuzz-gpu.so",
            "**/libharfbuzz-raster.so",
            "**/libharfbuzz-subset.so",
            "**/libharfbuzz-vector.so",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Copies the bundled theme into the asset tree as themes/<name>.
val copyThemeAssets by tasks.registering(Sync::class) {
    from("$repoRoot/themes/$bundledTheme") { into("themes/$bundledTheme") }
    into(layout.buildDirectory.dir("generated/themeAssets"))
}

// The Android strings are maintained in locale/android_strings.xml together with the other
// translatable strings.
val copyStringResources by tasks.registering(Copy::class) {
    from("$repoRoot/locale/android_strings.xml")
    into(layout.buildDirectory.dir("generated/stringRes/values"))
    rename { "strings.xml" }
}

tasks.named("preBuild") {
    dependsOn(copyThemeAssets, copyStringResources)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
