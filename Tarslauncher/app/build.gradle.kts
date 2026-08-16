import java.util.Properties
import java.security.MessageDigest
import java.io.FileInputStream

fun generateBuildHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
        val sha = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (sha.isNotEmpty() && !sha.contains("fatal")) {
            sha
        } else {
            "dev-${System.currentTimeMillis().toString().takeLast(7)}"
        }
    } catch (e: Exception) {
        "dev-${System.currentTimeMillis().toString().takeLast(7)}"
    }
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}


android {
    namespace = "com.tarslauncher.app"
    compileSdk = 36

    // --- Release signing configuration ---
    signingConfigs {
        create("release") {
            // Load keystore credentials from local.properties
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))
            }

            // Keystore credentials
            storePassword = localProperties.getProperty("storePassword")
            keyAlias = localProperties.getProperty("keyAlias")
            keyPassword = localProperties.getProperty("keyPassword")
            
            // Allow custom keystore path from local.properties
            val customStorePath = localProperties.getProperty("storeFile")
            if (customStorePath != null) {
                storeFile = file(customStorePath)
            } else {
                storeFile = rootProject.file("tarslauncher-release.jks")
            }
        }
    }

    defaultConfig {
        applicationId = "com.tarslauncher.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // Disable split APKs to generate single APK
        ndk {
            abiFilters.clear()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Explicitly set signing config to our release config
            signingConfig = signingConfigs.getByName("release")
            
            // Additional security settings
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            
            // Security build config fields
            buildConfigField("boolean", "SECURITY_ENABLED", "true")
            buildConfigField("String", "BUILD_TIMESTAMP", "\"${System.currentTimeMillis()}\"")
            buildConfigField("String", "BUILD_HASH", "\"${generateBuildHash()}\"")
            
            // Zipalign is enabled by default in modern AGP
        }
        
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            versionNameSuffix = "-debug"
            // Use default debug signing instead of release signing to fix build
            // signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    
    // Disable App Bundle and force APK generation
    bundle {
        language {
            enableSplit = false
        }
        density {
            enableSplit = false
        }
        abi {
            enableSplit = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
        implementation(libs.material3)
    implementation("androidx.compose.material:material-icons-extended:1.6.7")
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.coil.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.gson)
    
    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}