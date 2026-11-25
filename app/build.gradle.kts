import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// client_id de la OAuth App de GitHub (no es secreto). En local.properties: gitly.githubClientId=Ov23...
val gitlyProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val githubClientSecret: String = gitlyProps.getProperty("gitly.githubClientSecret")
    ?: providers.gradleProperty("gitly.githubClientSecret").orNull ?: ""
val githubClientId: String = gitlyProps.getProperty("gitly.githubClientId")
    ?: providers.gradleProperty("gitly.githubClientId").orNull ?: ""

android {
    namespace = "com.david.gitly"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.david.gitly"
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 6
        versionName = "0.6"
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")
        buildConfigField("String", "GITHUB_CLIENT_SECRET", "\"$githubClientSecret\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Móvil (arm64) y emulador (x86_64): la librería nativa de Nostr pesa ~10 MB por ABI.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
            optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Mensajes directos: Nostr (NIP-17/44/59), la misma librería que Knot.
    implementation("org.nostrdevkit:nostr-sdk:0.45.1")
    // Notificaciones: sondeo de respaldo + timbre UnifiedPush (ntfy) sin servidor propio
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.unifiedpush.android:connector:3.3.5")
    testImplementation("junit:junit:4.13.2")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
}
