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
    defaultConfig {
        applicationId = namespace
        minSdk = 35
        targetSdk = 37
        versionName = "0.1.0"
        versionCode = 1
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
    testImplementation("junit:junit:4.13.2")
}
