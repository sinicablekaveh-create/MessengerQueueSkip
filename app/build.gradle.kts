plugins { id("com.android.application") }

android {
    namespace = "com.sinicable.messengerqueue"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sinicable.threeib"
        minSdk = 26
        ndk { abiFilters.add("arm64-v8a") }
        targetSdk = 35
        versionCode = 24
        versionName = "4.0.0"
    }

    buildFeatures { buildConfig = true }

    flavorDimensions += "channel"
    productFlavors {
        create("bale") {
            dimension = "channel"
            applicationId = "com.sinicable.threeib.bale"
            manifestPlaceholders["appLabel"] = "THREE IB • Bale"
            buildConfigField("String", "TARGET_CHANNEL", "\"bale\"")
        }
        create("rubika") {
            dimension = "channel"
            applicationId = "com.sinicable.threeib.rubika"
            manifestPlaceholders["appLabel"] = "THREE IB • Rubika"
            buildConfigField("String", "TARGET_CHANNEL", "\"rubika\"")
        }
        create("eitaa") {
            dimension = "channel"
            applicationId = "com.sinicable.threeib.eitaa"
            manifestPlaceholders["appLabel"] = "THREE IB • Eitaa"
            buildConfigField("String", "TARGET_CHANNEL", "\"eitaa\"")
        }
        create("telegram") {
            dimension = "channel"
            applicationId = "com.sinicable.threeib.telegram"
            manifestPlaceholders["appLabel"] = "THREE IB • Telegram"
            buildConfigField("String", "TARGET_CHANNEL", "\"telegram\"")
        }
        create("sms") {
            dimension = "channel"
            applicationId = "com.sinicable.threeib.sms"
            manifestPlaceholders["appLabel"] = "THREE IB • SMS"
            buildConfigField("String", "TARGET_CHANNEL", "\"sms\"")
        }
        create("whatsapp") {
            dimension = "channel"
            applicationId = "com.sinicable.threeib.whatsapp"
            manifestPlaceholders["appLabel"] = "THREE IB • WhatsApp Business"
            buildConfigField("String", "TARGET_CHANNEL", "\"whatsapp\"")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols.add("**/libgoomni.so")
        }
    }

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
