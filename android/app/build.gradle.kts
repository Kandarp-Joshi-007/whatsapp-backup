plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kandarp.chatarchive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kandarp.chatarchive"
        minSdk = 30          // Android 11+: needed for "All files access"
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    // One fixed signing key, so every new APK installs over the old one and keeps the
    // same SHA-1 that is registered for Google sign-in. Personal app: key is kept in the repo.
    signingConfigs {
        create("personal") {
            storeFile = file("../keystore/chatarchive.jks")
            storePassword = "chatarchive"
            keyAlias = "chatarchive"
            keyPassword = "chatarchive"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("personal") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDirs(layout.buildDirectory.dir("generated/viewerAssets"))
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
        }
    }
}

// The WhatsApp-style screens are the same web viewer used on GitHub Pages, bundled into the APK.
val copyViewer by tasks.registering(Copy::class) {
    from(rootProject.file("../viewer")) { exclude("demo/**", "sw.js") }
    into(layout.buildDirectory.dir("generated/viewerAssets/viewer"))
}
tasks.named("preBuild") { dependsOn(copyViewer) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
