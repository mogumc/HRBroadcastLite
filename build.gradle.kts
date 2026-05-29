plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

project(":app").afterEvaluate {
    tasks.named("assembleRelease") {
        doLast {
            val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val source = releaseDir.resolve("app-release.apk")
            val versionName = rootProject.findProperty("heartwithClientVersionName") ?: android.defaultConfig.versionName
            val target = releaseDir.resolve("HRBroadcastLite-$versionName-release.apk")
            if (source.exists()) source.copyTo(target, overwrite = true)
        }
    }
}