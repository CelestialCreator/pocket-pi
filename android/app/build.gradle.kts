plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.zosma.pocketpi"
    compileSdk = 34

    defaultConfig {
        // TEMP for emulator validation. The upstream Termux bootstrap zip
        // ships compiled binaries (apt, dpkg, busybox helpers) with the path
        // /data/data/com.termux/files/usr baked in. Until we rebuild a
        // custom bootstrap pinned to our real prefix, run as com.termux so
        // that Android's chosen filesDir matches what the binaries expect.
        // Production build: applicationId = "com.zosma.pocketpi" + a custom
        // bootstrap built via termux-packages/scripts/build-bootstraps.sh.
        applicationId = "com.termux"
        minSdk = 24
        // CRITICAL: Termux itself caps targetSdk at 28. Android 10+ enforces
        // W^X on the app's private data directory when targetSdk >= 29 — any
        // execve() of a file under /data/user/0/<pkg>/files/ is blocked with
        // EACCES regardless of file mode bits. Capping at 28 keeps the
        // legacy semantics that allow exec from app-data, which Pi (and the
        // entire Termux runtime) relies on.
        targetSdk = 28
        versionCode = 6
        versionName = "0.4.0"

        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Pocket Pi mirrors Termux's install layout — the bootstrap zip extracts
    // into /data/data/com.zosma.pocketpi/files/usr at first launch.
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { /* defaults */ }
    }

    // Ship the bootstrap zip as a raw asset; PocketPiBootstrap unzips on first
    // run. The build script in bootstrap/build-bootstrap.sh produces this.
    sourceSets["main"].assets.srcDir("../../bootstrap/dist")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST")
        // Required when AndroidManifest sets extractNativeLibs="true". Pocket
        // Pi needs that flag so .so files end up in nativeLibraryDir with
        // executable permission, which is the only reliable way to ship
        // executables to the device on Android 6+.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.work)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
}
