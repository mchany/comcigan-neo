import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val emulatorSmoke = providers.gradleProperty("emulatorSmoke").orNull == "true"
val releaseSmoke = providers.gradleProperty("releaseSmoke").orNull == "true"
require(!releaseSmoke || emulatorSmoke) { "Release smoke tests require the isolated emulatorSmoke package" }
val neisProperties = Properties().apply {
    val file = rootProject.file("neis.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val neisApiKey = providers.environmentVariable("NEIS_API_KEY").orNull
    ?: neisProperties.getProperty("NEIS_API_KEY", "")
val neisKeyLiteral = "\"" + neisApiKey.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\r", "").replace("\n", "") + "\""
android {
    namespace = "io.github.mchany.comciganneo"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.mchany.comciganneo" + if (emulatorSmoke) ".smoke" else ""
        manifestPlaceholders["appLabel"] = if (emulatorSmoke) "컴시간neo 테스트" else "@string/app_name"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.0"
        resourceConfigurations += listOf("ko", "en")
        buildConfigField("String", "NEIS_API_KEY", neisKeyLiteral)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Exercise a real optimized APK upgrade in isolation, without release credentials.
            if (releaseSmoke) signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false
    }
}
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enableAndroidTest = emulatorSmoke }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:data"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation(kotlin("test-junit"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-runtime:2.7.1")
    androidTestImplementation("androidx.room:room-ktx:2.7.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
