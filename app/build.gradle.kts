plugins {
    id("com.android.application")
}

android {
    namespace = "com.pocketbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.5.4"
        if (project.findProperty("pocketbridgeUniversal") != "true") {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("io.github.webrtc-sdk:android:150.7871.04")
}
