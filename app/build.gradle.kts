plugins { id("com.android.application") }
android {
    namespace = "com.sinicable.messengerqueue"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sinicable.messengerqueue"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "2.1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
