import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// The xAI API key is injected at build time. Priority: env var XAI_API_KEY, then local.properties.
// Never commit local.properties; never log this value.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val xaiApiKey: String = providers.environmentVariable("XAI_API_KEY").orNull
    ?.takeIf { it.isNotBlank() }
    ?: localProps.getProperty("XAI_API_KEY", "")
// Voice + model are configurable without code changes (-PgrokVoice=sal, -PgrokModel=...).
val grokVoice: String = (findProperty("grokVoice") as String?) ?: "sal"
val grokModel: String = (findProperty("grokModel") as String?) ?: "grok-voice-think-fast-2.0"

fun String.asBuildConfigString() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.jsalinas.grokwatch"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jsalinas.grokwatch"
        minSdk = 33 // Wear OS 4+
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "XAI_API_KEY", xaiApiKey.asBuildConfigString())
        buildConfigField("String", "GROK_VOICE", grokVoice.asBuildConfigString())
        buildConfigField("String", "GROK_MODEL", grokModel.asBuildConfigString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val wearCompose = "1.7.0"
    val room = "2.8.5"
    val lifecycle = "2.11.0"

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.wear.compose:compose-material3:$wearCompose")
    implementation("androidx.wear.compose:compose-foundation:$wearCompose")
    implementation("androidx.wear.compose:compose-navigation:$wearCompose")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycle")

    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
