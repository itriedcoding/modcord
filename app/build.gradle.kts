plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.nebula.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.nebula.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3.0"
        vectorDrawables.useSupportLibrary = true
        // The app ships English UI text only; narrowing locales removes duplicated
        // Material/AndroidX translations from resources.arsc and res/.
        resourceConfigurations.add("en")
    }

    signingConfigs {
        create("release") {
            // Development signing key, checked in so that sideloaded builds upgrade cleanly.
            storeFile = file("../keystore/nebula-dev.jks")
            storePassword = "nebula"
            keyAlias = "nebula"
            keyPassword = "nebula"
        }
    }

    buildTypes {
        release {
            // Safe only because every JNI-visible class is kept explicitly in
            // proguard-rules.pro; the built DEX is checked with dexdump afterwards.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // QuickJS: real ES2020 JavaScript (classes, async/await, promises) with prebuilt
    // native libraries for every Android ABI. Rhino was evaluated first and rejected:
    // it cannot parse `class`, which every BetterDiscord plugin uses.
    implementation("io.github.taoweiji.quickjs:quickjs-android:1.4.6")

    // Real APK signing and signature verification (v1 + v2 + v3) for the Discord patcher.
    // This is the same implementation Android Studio and apksigner use.
    implementation("com.android.tools.build:apksig:8.5.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
