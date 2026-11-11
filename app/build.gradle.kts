plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.jasowantadas.ankiheatmap"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jasowantadas.ankiheatmap"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("shared") {
            storeFile = file("signing.keystore")
            storePassword = "ankiheatmap"
            keyAlias = "ankiheatmap"
            keyPassword = "ankiheatmap"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    // "standard" works out the widget size from the launcher. "oppo" uses fixed widths for the
    // Oppo tablet, whose launcher reports the wrong size: about 5 inches (800 dp) in portrait and
    // about 8 inches (1280 dp) in landscape. 160 dp = 1 inch.
    flavorDimensions += "device"
    productFlavors {
        create("standard") {
            dimension = "device"
            buildConfigField("int", "FIXED_PORTRAIT_WIDTH_DP", "0")
            buildConfigField("int", "FIXED_LANDSCAPE_WIDTH_DP", "0")
        }
        create("oppo") {
            dimension = "device"
            buildConfigField("int", "FIXED_PORTRAIT_WIDTH_DP", "800")
            buildConfigField("int", "FIXED_LANDSCAPE_WIDTH_DP", "1280")
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
