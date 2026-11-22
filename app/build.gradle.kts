plugins {
    id("com.android.application") version "9.0.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    val enyoId = listOf("space", "koyu", "enyo").joinToString(".")
    namespace = enyoId
    compileSdk = 36
    defaultConfig {
        applicationId = enyoId
        minSdk = 31
        targetSdk = 36
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
}
