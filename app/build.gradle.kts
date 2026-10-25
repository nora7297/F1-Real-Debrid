import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val ciVersionCode = (System.getenv("VERSION_CODE") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.f1debrid.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.f1debrid.app"
        minSdk = 25
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"

        // Optional Reddit "installed app" client ID baked into the build (not a secret for that app type),
        // so every install can use Reddit's official API without any setup. Empty = anonymous access.
        val redditClientId = (project.findProperty("f1debrid.redditClientId") as String?)?.trim().orEmpty()
        buildConfigField("String", "REDDIT_CLIENT_ID", "\"$redditClientId\"")
    }

    signingConfigs {
        create("release") {
            // A dedicated keystore is committed so every CI build is signed with the same key and
            // installs as an update. Override with env vars if you want to use your own key.
            storeFile = file(System.getenv("F1DEBRID_KEYSTORE") ?: rootProject.file("ci/f1debrid.jks").absolutePath)
            storePassword = System.getenv("F1DEBRID_KEYSTORE_PASSWORD") ?: "f1debrid"
            keyAlias = System.getenv("F1DEBRID_KEY_ALIAS") ?: "f1debrid"
            keyPassword = System.getenv("F1DEBRID_KEY_PASSWORD") ?: "f1debrid"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
