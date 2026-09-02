import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.miit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.miit.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    sourceSets["main"].apply {
        java.setSrcDirs(listOf("band", "core"))
        manifest.srcFile("AndroidManifest.xml")
    }
}

kotlin { jvmToolchain(17) }

// Gradle 8.9 can report implicit-dependency validation errors for Android
// dependency-processing outputs used by Kotlin compilation. These tasks must run
// before Kotlin compilation when they are present, but must not be hard dependencies
// (some AGP task graphs would otherwise form a cycle).
tasks.withType<KotlinCompile>().configureEach {
    mustRunAfter(tasks.matching {
        it.name.startsWith("check") && it.name.endsWith("DuplicateClasses")
    })
    mustRunAfter(tasks.matching {
        it.name.startsWith("desugar") && it.name.endsWith("FileDependencies")
    })
    mustRunAfter(tasks.matching {
        it.name.startsWith("merge") && it.name.endsWith("JniLibFolders")
    })
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
}
