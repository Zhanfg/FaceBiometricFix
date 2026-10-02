import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val signingProps = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.way.facebiometricfix"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.way.facebiometricfix"
        minSdk = 35
        targetSdk = 36
        versionCode = 9
        versionName = "2.2-test4-hotfix1"

    }

    signingConfigs {
        create("release") {
            storeFile = file(signingProps.getProperty("storeFile", "release.jks"))
            storePassword = signingProps.getProperty("storePassword", "")
            keyAlias = signingProps.getProperty("keyAlias", "")
            keyPassword = signingProps.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
