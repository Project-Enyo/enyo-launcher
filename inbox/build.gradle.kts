plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "space.koyu.enyo.inbox"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

// JitPack builds this module from a tag of the GitHub repository; see jitpack.yml.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.Project-Enyo.enyo-launcher"
            artifactId = "inbox"
            version = providers.gradleProperty("inboxVersion").getOrElse("1.0.0")
            afterEvaluate { from(components["release"]) }
        }
    }
}
