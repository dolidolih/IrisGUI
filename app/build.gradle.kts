plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.chaquo.python")
}

android {
    namespace = "party.qwer.irisgui"
    compileSdk = 35

    defaultConfig {
        applicationId = "party.qwer.irisgui"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material.v1110)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.org.json)
    implementation("net.zetetic:sqlcipher-android:4.13.0")
    implementation("androidx.sqlite:sqlite:2.7.0")
    testImplementation(libs.junit)
}

chaquopy {
    defaultConfig {
        version = "3.12"
        // 시스템 python3 은 3.14 → 앱 버전과 불일치하므로 명시 지정.
        buildPython("/home/dolidoli/.local/bin/python3.12")
        pyc {
            src = false
        }
        pip {
            // 순수 파이썬은 pypi.org, C 확장 네이티브 wheel 은 chaquopy 가 미리
            // 빌드해둔 인덱스에서 각각 받는다. irispy-client 실행에 필요한 것만.
            options("--extra-index-url", "https://chaquo.com/pypi-13.1/")
            install("requests")
            install("websockets")
            install("httpx")
            install("pillow")
        }
    }
}
