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
        versionCode = 3
        versionName = "0.3.0"
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
