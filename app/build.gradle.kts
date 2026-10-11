import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}
val releaseSigning = mapOf(
    "storeFile" to "RELEASE_STORE_FILE",
    "storePassword" to "RELEASE_STORE_PASSWORD",
    "keyAlias" to "RELEASE_KEY_ALIAS",
    "keyPassword" to "RELEASE_KEY_PASSWORD",
).mapValues { (localName, propertyName) ->
    providers.gradleProperty(propertyName).orNull ?: keystoreProperties.getProperty(localName)
}
val releaseStoreFile = releaseSigning["storeFile"]?.takeIf { it.isNotBlank() }?.let(::file)
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    val configured = releaseSigning.values.all { !it.isNullOrBlank() }
    val store = releaseStoreFile
    doLast {
        check(configured) { "Release signing requires keystore.properties or RELEASE_* Gradle properties" }
        check(store?.isFile == true) { "Release keystore file does not exist" }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseSigning)
}

android {
    namespace = "com.jasongzy.classicback"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.jasongzy.classicback"
        minSdk = 33
        targetSdk = 37
        versionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").get()
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile
            storePassword = releaseSigning["storePassword"]
            keyAlias = releaseSigning["keyAlias"]
            keyPassword = releaseSigning["keyPassword"]
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.merges += "META-INF/xposed/*"
}

dependencies {
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
}
