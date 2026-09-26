import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing credentials, kept out of the repo. Absent on a fresh clone, in which
// case the release build simply comes out unsigned rather than failing.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.crylo.slimpdf"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.crylo.slimpdf"
        // 29 is the first API level with a guaranteed system dark-mode signal, which lets
        // the night theme come from resource qualifiers instead of an AppCompat dependency.
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // minSdk 29 is well past v1's API 24 cutoff, so the JAR signature is
                // dead weight. v3 is what allows the signing key to be rotated later
                // without orphaning existing installs.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Keeps the dependency metadata block out of the APK; it is signed metadata we have
    // no use for and it is pure weight in an app this small.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // The Kotlin stdlib drags a handful of jar metadata entries along with it. None of
    // them are read at runtime on Android.
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "/META-INF/com/android/build/gradle/*",
                "/kotlin/**",
                "/DebugProbesKt.bin",
                "**/*.kotlin_builtins",
                "**/*.kotlin_metadata",
            )
        }
    }
}

dependencies {
    // Nothing on the app classpath: no AndroidX, no Compose, no PDF engine. Anything
    // added here is a size decision, not a convenience one.
    //
    // Instrumentation-only. These never reach the shipped APK, which is what lets the
    // gesture and zoom behaviour be tested at all -- SELinux blocks raw event injection
    // on a Play system image, so the events have to come from inside the process.
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}
