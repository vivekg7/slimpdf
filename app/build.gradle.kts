plugins {
    id("com.android.application")
}

android {
    namespace = "com.crylo.slimpdf"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.crylo.slimpdf"
        // 29 is the first API level with a guaranteed system dark-mode signal, which lets
        // the night theme come from resource qualifiers instead of an AppCompat dependency.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
