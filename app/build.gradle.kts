plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kim.austopo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kim.austopo"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    // Reads from (in order): -PaustopoKeystore=… etc on the command line,
    // ~/.gradle/gradle.properties, environment variables AUSTOPO_KEYSTORE,
    // AUSTOPO_KEYSTORE_PASSWORD, AUSTOPO_KEY_ALIAS, AUSTOPO_KEY_PASSWORD.
    // If the keystore file is absent, release falls back to the debug keystore
    // so local `assembleRelease` still works for sideloading.
    val keystorePath = (findProperty("austopoKeystore") as String?)
        ?: System.getenv("AUSTOPO_KEYSTORE")
    val haveReleaseKeystore = keystorePath != null && file(keystorePath).exists()

    signingConfigs {
        if (haveReleaseKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = (findProperty("austopoKeystorePassword") as String?)
                    ?: System.getenv("AUSTOPO_KEYSTORE_PASSWORD")
                keyAlias = (findProperty("austopoKeyAlias") as String?)
                    ?: System.getenv("AUSTOPO_KEY_ALIAS")
                keyPassword = (findProperty("austopoKeyPassword") as String?)
                    ?: System.getenv("AUSTOPO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (haveReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
