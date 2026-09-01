plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "me.naptie.pulse.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.naptie.pulse.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = (project.findProperty("pulse.version") as String?) ?: "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity)
    implementation(libs.material3)
    implementation(libs.foundation)
}
