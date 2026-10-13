plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.omnifusion.mobile"
 compileSdk = 35
 defaultConfig { applicationId = "com.omnifusion.mobile"; minSdk = 26; targetSdk = 35; versionCode = 6; versionName = "2.0.0-beta2" }
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
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
