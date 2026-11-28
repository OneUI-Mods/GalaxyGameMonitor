import java.util.Properties

plugins { id("com.android.application") }

val distribution = Properties().apply {
    val config = rootProject.file(".signing/release.properties")
    if (config.isFile) config.reader().use { load(it) }
}

android {
    namespace = "dev.ryuya.galaxygamemonitor"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = namespace
        minSdk = 37
        targetSdk = 37
        versionName = "0.4.23"
        versionCode = 36
    }
    signingConfigs {
        if (distribution.isNotEmpty()) {
            create("distribution") {
                storeFile = rootProject.file(distribution.getProperty("storeFile"))
                storePassword = distribution.getProperty("storePassword")
                keyAlias = distribution.getProperty("keyAlias")
                keyPassword = distribution.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("distribution")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
}
