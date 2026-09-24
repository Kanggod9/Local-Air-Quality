plugins {
    id("com.android.application")
}

android {
    namespace = "com.localairquality.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.localairquality.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.4.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles("proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    buildFeatures {
        buildConfig = true
    }
}


dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}





