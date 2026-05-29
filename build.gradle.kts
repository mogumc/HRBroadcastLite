plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

afterEvaluate {
    val versionName = project(":app").extensions.getByType(com.android.build.gradle.AppExtension::class.java).defaultConfig.versionName ?: "unknown"
    tasks.named("assembleRelease") {
        doLast {
            val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val source = releaseDir.resolve("app-release.apk")
            val target = releaseDir.resolve("HRBroadcastLite-$versionName-release.apk")
            if (source.exists()) source.copyTo(target, overwrite = true)
        }
    }
}