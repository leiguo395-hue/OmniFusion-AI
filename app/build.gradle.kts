plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.omnifusion.mobile"
 compileSdk = 35
 defaultConfig { applicationId = "com.omnifusion.mobile"; minSdk = 26; targetSdk = 35; versionCode = 7; versionName = "2.0.0-beta3" }
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
 }
 dependencies { implementation("com.github.QWEA0:liquidglass:v2.0.11") }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
