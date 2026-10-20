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
    id("org.jetbrains.kotlin.plugin.compose")
}


android {
    namespace = "com.tarslauncher.app"
    compileSdk = 36

    // Load local.properties or environment variables for keystore configs
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
    }

    val releaseStoreFilePath = System.getenv("RELEASE_STORE_FILE")
        ?: localProperties.getProperty("RELEASE_STORE_FILE")
        ?: "tarslauncher-release.jks"
    val releaseStoreFile = rootProject.file(releaseStoreFilePath)

    val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
        ?: localProperties.getProperty("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
        ?: localProperties.getProperty("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        ?: localProperties.getProperty("RELEASE_KEY_PASSWORD")

    signingConfigs {
        create("release") {
            if (releaseStoreFile.exists() && !releaseStorePassword.isNullOrEmpty()) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias ?: "tarslauncher"
                keyPassword = releaseKeyPassword ?: releaseStorePassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.tarslauncher.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 70
        versionName = "2.0.21"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // Disable split APKs to generate single APK
        ndk {
            abiFilters.clear()
        }

        // Strip unused localized strings and assets from AndroidX/Material libraries
        resourceConfigurations += listOf("en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
            
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "DebugProbesKt.bin"
            excludes += "**/*.kotlin_builtins"
            excludes += "**/*.version"
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
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.gson)    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}