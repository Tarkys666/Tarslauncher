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
        versionCode = 47
        versionName = "1.8.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // Disable split APKs to generate single APK
        ndk {
            abiFilters.clear()
        }

        // Supabase configuration parameterized from local.properties or env vars, with production default fallback
        val defaultSupabaseUrl = "https://nznftexnhmzboihbeilq.supabase.co"
        val defaultSupabaseAnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im56bmZ0ZXhuaG16Ym9paGJlaWxxIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTIzMzk0OTUsImV4cCI6MjEwNzkxNTQ5NX0.FZo6dEMZNbe33Bj8VjQ6VZ57j-O-y464YPJQXDS6kD0"

        val supabaseUrl = (System.getenv("SUPABASE_URL") ?: localProperties.getProperty("SUPABASE_URL"))
            ?.takeIf { it.isNotBlank() } ?: defaultSupabaseUrl
        val supabaseAnonKey = (System.getenv("SUPABASE_ANON_KEY") ?: localProperties.getProperty("SUPABASE_ANON_KEY"))
            ?.takeIf { it.isNotBlank() } ?: defaultSupabaseAnonKey

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
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
    implementation(libs.androidx.browser)
    implementation(libs.gson)
    
    // Google Auth (Supabase Cloud Identity)
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}