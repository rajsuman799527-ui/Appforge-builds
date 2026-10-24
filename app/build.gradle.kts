import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val af = Properties().apply {
    val f = rootProject.file("appforge.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.appforge.shell"
    compileSdk = 34
    defaultConfig {
        applicationId = af.getProperty("applicationId", "com.appforge.demo")
        minSdk = 24
        targetSdk = 34
        versionCode = af.getProperty("versionCode", "1").toInt()
        versionName = af.getProperty("versionName", "1.0.0")
    }
    signingConfigs {
        create("release") {
            val ks = rootProject.file("release.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.onesignal:OneSignal:5.1.17")
}
