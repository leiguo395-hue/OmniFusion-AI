plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.omnifusion.mobile"
 compileSdk = 35
 defaultConfig { applicationId = "com.omnifusion.mobile"; minSdk = 26; targetSdk = 35; versionCode = 13; versionName = "2.0.0-beta8" }
 val releaseKey = System.getenv("OMNI_KEYSTORE_PATH")
 if (!releaseKey.isNullOrBlank()) {
   signingConfigs {
     create("omniRelease") {
       storeFile = file(releaseKey)
       storePassword = System.getenv("OMNI_STORE_PASSWORD")
       keyAlias = System.getenv("OMNI_KEY_ALIAS")
       keyPassword = System.getenv("OMNI_KEY_PASSWORD")
     }
   }
   buildTypes.getByName("release").signingConfig = signingConfigs.getByName("omniRelease")
   buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("omniRelease")
 }
 // Debug builds install alongside the existing app when no matching signing key exists.
 // This prevents uninstalling the user's old app and losing its data.
 buildTypes.getByName("debug").applicationIdSuffix = ".beta"
 buildTypes.getByName("debug").versionNameSuffix = "-parallel"
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}

dependencies {
 implementation("com.github.QWEA0:liquidglass:v2.0.11")
 implementation("androidx.core:core-ktx:1.13.1")
}
