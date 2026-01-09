plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "cn.keevol.keenotes"
    compileSdk = 34

    defaultConfig {
        applicationId = "cn.keevol.keenotes"
        minSdk = 26
        targetSdk = 34
        
        // Version from gradle properties or default
        val versionNameProp = project.findProperty("versionName") as String? ?: "1.0.0"
        val versionCodeProp = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = versionCodeProp
        versionName = versionNameProp
        
        // Set APK output name
        setProperty("archivesBaseName", "keenotes-android-$versionName")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Use default debug keystore
        }
        create("release") {
            // Read from environment variables (for CI/CD) or gradle.properties (for local)
            val keystoreFile = System.getenv("KEYSTORE_FILE") 
                ?: project.findProperty("KEYSTORE_FILE") as String?
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD") 
                ?: project.findProperty("KEYSTORE_PASSWORD") as String?
            val keyAlias = System.getenv("KEY_ALIAS") 
                ?: project.findProperty("KEY_ALIAS") as String?
            val keyPassword = System.getenv("KEY_PASSWORD") 
                ?: project.findProperty("KEY_PASSWORD") as String?
            
            if (keystoreFile != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            } else {
                // Fallback to debug signing if release signing not configured
                println("WARNING: Release signing not configured, using debug keystore")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Use release signing if configured, otherwise fall back to debug
            signingConfig = if (signingConfigs.findByName("release") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // OkHttp for networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // Crypto - BouncyCastle for Argon2
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
