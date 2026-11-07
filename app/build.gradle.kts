plugins { id("com.android.application") }
android {
    namespace = "com.sinicable.messengerqueue"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sinicable.threeib.personal"
        minSdk = 26
        ndk { abiFilters.add("arm64-v8a") }
        targetSdk = 35
        versionCode = 22
        versionName = "3.3.2"
    }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols.add("**/libgoomni.so") } }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    implementation("io.github.tdlib-android:core:0.1.1")
}
