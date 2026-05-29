val HRBroadcastLiteVersionCode = (findProperty("HRBroadcastLiteClientVersionCode") as? String)?.toIntOrNull() ?: 1
val HRBroadcastLiteVersionName = (findProperty("HRBroadcastLiteClientVersionName") as? String) ?: "1.0"

val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
if (!debugKeystore.exists()) {
    debugKeystore.parentFile.mkdirs()
    ProcessBuilder(
        "keytool", "-genkey", "-v", "-keystore", debugKeystore.absolutePath,
        "-alias", "androiddebugkey",
        "-storepass", "android",
        "-keypass", "android",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Android Debug,O=Android,C=US"
    ).inheritIO().start().waitFor()
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hrbroadcast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hrbroadcast.lite"
        minSdk = 27
        targetSdk = 35
        versionCode = HRBroadcastLiteVersionCode
        versionName = HRBroadcastLiteVersionName
    }

    signingConfigs {
        getByName("debug") {
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.material:material:1.12.0")
}

afterEvaluate {
    tasks.named("assembleRelease") {
        doLast {
            val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val apk = releaseDir.listFiles()?.find { it.name.endsWith(".apk") && !it.name.contains("HRBroadcastLite") }
            if (apk != null) {
                apk.copyTo(
                    releaseDir.resolve("HRBroadcastLite-$HRBroadcastLiteVersionName-release.apk"),
                    overwrite = true
                )
            }
        }
    }
}
