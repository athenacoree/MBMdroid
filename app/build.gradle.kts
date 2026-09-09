import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.locol.mbmdroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.locol.mbmdroid"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            val keystoreBase64 = System.getenv("KEYSTORE_BASE64") ?: System.getenv("KEYSTOREBASE64")
            if (!keystoreBase64.isNullOrBlank()) {
                val keystoreFile = file("${layout.buildDirectory.get()}/release.keystore")
                keystoreFile.parentFile?.mkdirs()
                keystoreFile.writeBytes(Base64.getDecoder().decode(keystoreBase64.trim()))
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getenv("KEYSTORE_PASS") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: System.getenv("KEYALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYPASS") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Runtime de las mini-apps: sirve archivos locales al WebView de forma segura
    implementation("androidx.webkit:webkit:1.12.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    testImplementation("junit:junit:4.13.2")
}
