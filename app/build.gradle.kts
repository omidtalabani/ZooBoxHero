plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services) // Add this line for Google Services plugin
}

android {
    namespace = "com.zoobox.hero"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zoobox.hero"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
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
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended") // <-- Add this for all extended icons
    implementation ("com.squareup.okhttp3:okhttp:4.10.0")
    implementation ("com.squareup.okhttp3:okhttp-sse:4.10.0")
    implementation ("com.onesignal:OneSignal:[5.0.0, 5.99.99]")
    // Add SwipeRefresh for Compose
    implementation("com.google.accompanist:accompanist-swiperefresh:0.32.0")
    // Add this dependency for SwipeRefreshLayout
    implementation ("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // Add this line for ProcessLifecycleOwner - THIS IS THE NEW LINE
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")

    // WorkManager for enhanced background service reliability
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Add Firebase dependencies
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.analytics)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}