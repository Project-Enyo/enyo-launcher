plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    val enyoId = listOf("space", "koyu", "enyo").joinToString(".")
    namespace = enyoId
    compileSdk = 36
    defaultConfig {
        applicationId = enyoId
        minSdk = 31
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"
    }
    signingConfigs {
        create("release") {
            // Credentials come from the environment, so nothing secret lives in the repository.
            System.getenv("SIGNING_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("SIGNING_STORE_PASSWORD")
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            if (System.getenv("SIGNING_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
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
    implementation("net.zetetic:sqlcipher-android:4.20.0")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
}
