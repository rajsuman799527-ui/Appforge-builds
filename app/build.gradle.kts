import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val af = Properties().apply {
    val f = rootProject.file("appforge.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// One push provider per APK. Missing property (older projects) = OneSignal, exactly as before.
val beams = af.getProperty("pushProvider", "onesignal") == "beams"
if (beams) apply(plugin = "com.google.gms.google-services")

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
    sourceSets.getByName("main").java.srcDir(if (beams) "src/beams/java" else "src/onesignal/java")
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    if (beams) {
        // 1.10.0+ uses FirebaseMessaging.getToken; 1.9.x calls the removed FirebaseInstanceId and crashes on launch.
        implementation("com.pusher:push-notifications-android:1.10.0")
        implementation("com.google.firebase:firebase-messaging:23.4.1")
    } else {
        implementation("com.onesignal:OneSignal:5.1.17")
    }
}
