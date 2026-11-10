plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.weblauncher.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weblauncher.app"
        minSdk = 26
        targetSdk = 34
        // Su GitHub il numero di build cresce a ogni compilazione (1000 + numero dell'esecuzione),
        // cosi' l'app riconosce da sola le versioni piu' nuove.
        versionCode = System.getenv("WL_VERSION_CODE")?.toIntOrNull() ?: 45
        versionName = "7.7.0"
        // solo italiano e inglese: le librerie includono testi in ~80 lingue che l'app non usa
        resourceConfigurations += listOf("it", "en")
    }

    // Firma fissa: la chiave arriva dai secret di GitHub (WL_KEYSTORE_BASE64,
    // WL_KEYSTORE_PASSWORD). Con la stessa firma Android installa l'aggiornamento
    // sopra la versione precedente, senza disinstallare. Senza secret usa la
    // chiave di debug (solo per prove locali).
    val ksFile = System.getenv("WL_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    val ksPass = System.getenv("WL_KEYSTORE_PASSWORD")
    signingConfigs {
        if (ksFile != null && !ksPass.isNullOrEmpty()) create("fixed") {
            storeFile = ksFile
            storePassword = ksPass
            keyAlias = "weblauncher"
            keyPassword = ksPass
        }
    }
    val fixedSigning = signingConfigs.findByName("fixed")

    buildTypes {
        release {
            if (fixedSigning != null) signingConfig = fixedSigning
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // R8: toglie il codice inutilizzato delle librerie (APK molto più leggero)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (fixedSigning != null) signingConfig = fixedSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.browser:browser:1.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("io.coil-kt:coil:2.5.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    testImplementation("junit:junit:4.13.2")
}
