import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.offlineplayer.downloader"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// The only module that knows about yt-dlp. When YouTube changes, either tap
// "Update downloader" in the app (updates yt-dlp itself) or bump this version.
val ytdlAndroid = "0.18.1"

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlAndroid")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
