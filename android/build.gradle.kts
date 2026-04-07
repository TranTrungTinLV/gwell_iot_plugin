plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val GWIOT_NEXUS_BASE_URL = "https://nexus-sg.gwell.cc/nexus/repository/"

android {
    namespace = "com.foursgen.connect"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        dataBinding = true
    }

    packaging {
        jniLibs {
            pickFirsts += listOf(
                "lib/armeabi/libc++_shared.so",
                "lib/armeabi/libgwmarsxlog.so",
                "lib/armeabi/libavcodec.so",
                "lib/armeabi/libavfilter.so",
                "lib/armeabi/libavformat.so",
                "lib/armeabi/libavutil.so",
                "lib/armeabi/libcrypto.1.1.so",
                "lib/armeabi/libgwbase.so",
                "lib/armeabi/libssl.1.1.so",
                "lib/armeabi/libswresample.so",
                "lib/armeabi/libswscale.so",
                "lib/armeabi/libxml2.so",
                "lib/armeabi/libgwplayer.so",
                "lib/armeabi/libaudiodsp_dynamic.so",
                "lib/armeabi/libtxTraeVoip.so",
                "lib/armeabi/libcurl.so",
                "lib/armeabi/libijkffmpeg.so",
                "lib/armeabi/libijkplayer.so",
                "lib/armeabi/libijksdl.so",
                "lib/armeabi/libbleconfig.so",
                "lib/armeabi/libiotvideomulti.so",
                "lib/armeabi/libmbedtls.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/armeabi-v7a/libgwmarsxlog.so",
                "lib/armeabi-v7a/libavcodec.so",
                "lib/armeabi-v7a/libavfilter.so",
                "lib/armeabi-v7a/libavformat.so",
                "lib/armeabi-v7a/libavutil.so",
                "lib/armeabi-v7a/libcrypto.1.1.so",
                "lib/armeabi-v7a/libgwbase.so",
                "lib/armeabi-v7a/libssl.1.1.so",
                "lib/armeabi-v7a/libswresample.so",
                "lib/armeabi-v7a/libswscale.so",
                "lib/armeabi-v7a/libxml2.so",
                "lib/armeabi-v7a/libgwplayer.so",
                "lib/armeabi-v7a/libaudiodsp_dynamic.so",
                "lib/armeabi-v7a/libtxTraeVoip.so",
                "lib/armeabi-v7a/libcurl.so",
                "lib/armeabi-v7a/libijkffmpeg.so",
                "lib/armeabi-v7a/libijkplayer.so",
                "lib/armeabi-v7a/libijksdl.so",
                "lib/armeabi-v7a/libbleconfig.so",
                "lib/armeabi-v7a/libiotvideomulti.so",
                "lib/armeabi-v7a/libmbedtls.so",
                "lib/arm64-v8a/libc++_shared.so",
                "lib/arm64-v8a/libgwmarsxlog.so",
                "lib/arm64-v8a/libavcodec.so",
                "lib/arm64-v8a/libavfilter.so",
                "lib/arm64-v8a/libavformat.so",
                "lib/arm64-v8a/libavutil.so",
                "lib/arm64-v8a/libcrypto.1.1.so",
                "lib/arm64-v8a/libgwbase.so",
                "lib/arm64-v8a/libssl.1.1.so",
                "lib/arm64-v8a/libswresample.so",
                "lib/arm64-v8a/libswscale.so",
                "lib/arm64-v8a/libxml2.so",
                "lib/arm64-v8a/libgwplayer.so",
                "lib/arm64-v8a/libaudiodsp_dynamic.so",
                "lib/arm64-v8a/libtxTraeVoip.so",
                "lib/arm64-v8a/libcurl.so",
                "lib/arm64-v8a/libijkffmpeg.so",
                "lib/arm64-v8a/libijkplayer.so",
                "lib/arm64-v8a/libijksdl.so",
                "lib/arm64-v8a/libbleconfig.so",
                "lib/arm64-v8a/libiotvideomulti.so",
                "lib/arm64-v8a/libmbedtls.so"
            )
        }
    }
}

repositories {
    maven {
        url = uri("${GWIOT_NEXUS_BASE_URL}/maven-releases/")
        credentials {
            username = "iptime_eti_user"
            password = "6S1Moa^HFaL!rEqQC"
        }
        isAllowInsecureProtocol = true
    }
    maven {
        url = uri("${GWIOT_NEXUS_BASE_URL}/maven-gwiot/")
        credentials {
            username = "iptime_eti_user"
            password = "6S1Moa^HFaL!rEqQC"
        }
        isAllowInsecureProtocol = true
    }
    maven { url = uri("https://mvn.zztfly.com/android") }
    maven { url = uri("https://jitpack.io") }
    google()
    mavenCentral()
}

dependencies {
    // Gwell IoT API SDK
    implementation("com.gwell:gwiotapi:1.6.7.3")

    // Firebase (required by ML Kit & push module in Gwell SDK)
    implementation("com.google.firebase:firebase-common:21.0.0")

    // Yoosee/Gwell Plugin Hub - Google variant


    implementation("com.yoosee.gw_plugin_hub:impl_main:google-release-6.39.0.0.8") {
        exclude(group = "com.google.android.material")
        exclude(group = "com.yoosee.gw_plugin_hub", module = "liblog_release")
        exclude(group = "com.gwell", module = "iotvideo-multiplatform")
        exclude(group = "com.gwell", module = "cloud_player")
        exclude(group = "androidx.activity", module = "activity-ktx")
        exclude(group = "com.gwell", module = "gwiotapi")
        exclude(group = "com.tencentcs", module = "txtraevoip")
        // Fix duplicate classes/resources
        exclude(group = "cn.aigestudio.wheelpicker")
        exclude(group = "com.contrarywind")
        exclude(group = "com.eightbitlab", module = "blurview")
        exclude(group = "com.yoosee.gw_plugin_hub", module = "lib_m3u8manger")
    }

    implementation("com.reoqoo.gw_plugin_hub:main:dss-release-01.06.01.0.58") {
        exclude(group = "com.gwell", module = "gwiotapi")
        exclude(group = "cn.aigestudio.wheelpicker")
        exclude(group = "com.contrarywind")
        exclude(group = "com.eightbitlab", module = "blurview")
    }

    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
