plugins {
    id("com.android.application")
}

// Release builds can use the same CI-protected key on every run. Local debug
// builds keep the normal Android debug key when these variables are absent.
val pocketbridgeSigningStore = System.getenv("POCKETBRIDGE_SIGNING_STORE_FILE")
val pocketbridgeSigningStorePassword = System.getenv("POCKETBRIDGE_SIGNING_STORE_PASSWORD")
val pocketbridgeSigningAlias = System.getenv("POCKETBRIDGE_SIGNING_KEY_ALIAS")
val pocketbridgeSigningKeyPassword = System.getenv("POCKETBRIDGE_SIGNING_KEY_PASSWORD")

// Package the helper built from these same sources. This also works on Linux CI.
val helperRoot = rootProject.layout.projectDirectory.dir("tools/win-control")
val helperSource = helperRoot.dir("upstream-v0.1.0/web-remote-desktop-0.1.0")
val helperExecutable = layout.buildDirectory.file("windowsHelper/PocketBridge-Windows-Control.exe")
val helperAssets = layout.buildDirectory.dir("generated/windowsHelperAssets")
val compileWindowsHelper by tasks.registering(Exec::class) {
    inputs.files(fileTree(helperSource) {
        include("*.go", "go.mod", "go.sum", "web/**")
        exclude("*_test.go")
    }).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(helperExecutable)
    workingDir(helperSource)
    environment("GOOS", "windows")
    environment("GOARCH", "amd64")
    environment("CGO_ENABLED", "0")
    commandLine("go", "build", "-trimpath", "-buildvcs=false", "-ldflags=-s -w",
        "-o", helperExecutable.get().asFile.absolutePath, ".")
    doFirst { helperExecutable.get().asFile.parentFile.mkdirs() }
}
val packageWindowsHelper by tasks.registering(Zip::class) {
    dependsOn(compileWindowsHelper)
    from(helperExecutable)
    from(helperRoot.file("Start-Control.bat"), helperRoot.file("README.md"))
    from(helperSource.file("LICENSE")) { rename { "UPSTREAM-LICENSE.txt" } }
    archiveFileName.set("PocketBridge-Windows-Control.zip")
    destinationDirectory.set(helperAssets)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

android {
    namespace = "com.pocketbridge"
    compileSdk = 35

    if (!pocketbridgeSigningStore.isNullOrBlank()) {
        signingConfigs {
            create("pocketbridgeStable") {
                storeFile = file(pocketbridgeSigningStore)
                storePassword = pocketbridgeSigningStorePassword
                keyAlias = pocketbridgeSigningAlias
                keyPassword = pocketbridgeSigningKeyPassword
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("pocketbridgeStable")
            }
        }
    }

    defaultConfig {
        applicationId = "com.pocketbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "0.6.0.12"
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
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    androidResources { noCompress += "zip" }
    sourceSets.getByName("main").assets.directories.add(helperAssets.get().asFile.absolutePath)

    testOptions {
        // Robolectric needs the packaged resources to load the real assets/www page.
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.named("preBuild") { dependsOn(packageWindowsHelper) }

// Robolectric downloads its Android runtime, and Gradle test workers do not inherit
// the daemon's network settings. Forward them when they are set, without ever
// committing the values.
tasks.withType<Test>().configureEach {
    listOf(
        "https.proxyHost", "https.proxyPort", "https.proxyUser", "https.proxyPassword",
        "http.proxyHost", "http.proxyPort", "http.proxyUser", "http.proxyPassword",
        "javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword"
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
    // Local, offline Robolectric runtime when the build machine has pre-fetched it.
    System.getenv("POCKETBRIDGE_ROBO_DIR")?.let {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", it)
    }
}

dependencies {
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-video:8.1.7")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    // A real org.json on the unit-test classpath: the Android stub throws unless mocked.
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
