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

    // "standard" works out the widget size from the launcher. "oppo" uses a fixed widget size for
    // the Oppo tablet, whose launcher reports the wrong size. Measured from screenshots of the
    // widget as placed: portrait about 569 x 270 dp, landscape about 717 x 186 dp (160 dp = 1 inch).
    flavorDimensions += "device"
    productFlavors {
        create("standard") {
            dimension = "device"
            buildConfigField("int", "FIXED_PORTRAIT_WIDTH_DP", "0")
            buildConfigField("int", "FIXED_PORTRAIT_HEIGHT_DP", "0")
            buildConfigField("int", "FIXED_LANDSCAPE_WIDTH_DP", "0")
            buildConfigField("int", "FIXED_LANDSCAPE_HEIGHT_DP", "0")
        }
        create("oppo") {
            dimension = "device"
            buildConfigField("int", "FIXED_PORTRAIT_WIDTH_DP", "569")
            buildConfigField("int", "FIXED_PORTRAIT_HEIGHT_DP", "270")
            buildConfigField("int", "FIXED_LANDSCAPE_WIDTH_DP", "717")
            buildConfigField("int", "FIXED_LANDSCAPE_HEIGHT_DP", "186")
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
