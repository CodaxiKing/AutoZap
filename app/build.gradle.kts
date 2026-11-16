plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.codaxi.pinsave"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.codaxi.pinsave"
        minSdk = 29
        targetSdk = 34
        versionCode = 19
        versionName = "1.18"
    }

    // Chave de debug fixa no repositório: assim toda build tem a mesma
    // assinatura e dá para atualizar o app sem desinstalar.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Recodifica vídeos para aplicar a marca d'água.
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    testImplementation("junit:junit:4.13.2")
}
