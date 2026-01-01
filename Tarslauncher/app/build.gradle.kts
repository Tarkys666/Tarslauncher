import java.util.Properties
import java.security.MessageDigest

fun generateBuildHash(): String {
    val timestamp = System.currentTimeMillis().toString()
    val md = MessageDigest.getInstance("SHA-256")
    val hash = md.digest(timestamp.toByteArray())
    return hash.joinToString("") { "%02x".format(it) }.take(16)
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    id("com.google.gms.google-services")
}


android {
    namespace = "com.tarslauncher.app"
    compileSdk = 36

    // --- Release signing configuration ---
    signingConfigs {
        create("release") {
            // Always use the keystore in the project root
            storeFile = rootProject.file("tarslauncher-release.jks")
            
            // Keystore credentials
            storePassword = "M@v!5i_Star!ng"
            keyAlias = "tarslauncher_alias"
            keyPassword = "M@v!5i_Star!ng"
        }
    }

    defaultConfig {
        applicationId = "com.tarslauncher.app"
        minSdk = 26
        targetSdkVersion(36)
        versionCode = 9
        versionName = "1.0"

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
            // Use release signing for debug to avoid package conflicts
            signingConfig = signingConfigs.getByName("release")
            
            // Force single APK generation
            splits.abi.isEnable = false
            splits.density.isEnable = false
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
    implementation(libs.gson)
    implementation(libs.google.play.billing)
    implementation(libs.google.signin)
    
    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Firebase Firestore
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}