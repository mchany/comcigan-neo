import java.util.Properties
import org.tomlj.Toml
import groovy.json.JsonOutput
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.tomlj:tomlj:1.1.1") }
}

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val emulatorSmoke = providers.gradleProperty("emulatorSmoke").orNull == "true"
val releaseMetadata = readReleaseMetadata(rootProject.file("release.toml"))
val releaseVersion = releaseMetadata.getValue("version") as String
val releaseCode = releaseMetadata.getValue("version_code") as Long
val releaseSmoke = providers.gradleProperty("releaseSmoke").orNull == "true"
require(!releaseSmoke || emulatorSmoke) { "Release smoke tests require the isolated emulatorSmoke package" }
val neisProperties = Properties().apply {
    val file = rootProject.file("neis.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

tasks.register("testReleaseAutomation") {
    group = "verification"
    description = "Exercise release metadata and APK guardrails with synthetic fixtures."
    doLast {
        val directory = rootProject.layout.buildDirectory.dir("release-tests").get().asFile.apply { mkdirs() }
        val key = "synthetic-neis-key"
        val apk = File(directory, "fixture.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write("dex fixture $key".toByteArray())
            zip.closeEntry()
        }
        val badging = "package: name='io.github.mchany.comciganneo' versionCode='16' versionName='1.1.0'\nsdkVersion:'26'\n"
        verifyReleaseBadging(badging, "1.1.0", 16)
        verifyReleaseContents(apk, key)
        expectReleaseFailure { verifyReleaseBadging(badging.replace("comciganneo'", "comciganneo.smoke'"), "1.1.0", 16) }
        expectReleaseFailure { verifyReleaseBadging(badging + "application-debuggable\n", "1.1.0", 16) }
        expectReleaseFailure { verifyReleaseBadging(badging.replace("'26'", "'25'"), "1.1.0", 16) }
        expectReleaseFailure { verifyReleaseBadging(badging, "1.1.1", 16) }
        expectReleaseFailure { verifyReleaseContents(apk, "missing-key") }
        expectReleaseFailure { verifyReleaseContents(apk, "") }
        val privateApk = File(directory, "private.apk")
        ZipOutputStream(privateApk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/neis.properties"))
            zip.write("fixture".toByteArray())
            zip.closeEntry()
        }
        expectReleaseFailure { verifyReleaseContents(privateApk, key) }
        val invalid = File(directory, "invalid.toml").apply {
            writeText("[release]\nversion='01.1.0'\nversion_code=16\nsummary='fixture'\nchanges=['fixture']\n")
        }
        expectReleaseFailure { readReleaseMetadata(invalid) }
        logger.lifecycle("Release guardrail fixtures passed")
    }
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
        versionCode = releaseCode.toInt()
        versionName = releaseVersion
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

/**
 * 역할: TOML 릴리즈 입력을 엄격히 검사한다.
 * 입력: 버전·Android 코드·사용자 설명이 담긴 파일.
 * 출력: 검증된 JSON 직렬화용 정보이며 비정상 입력은 오류다.
 */
fun readReleaseMetadata(file: File): Map<String, Any> {
    val parsed = Toml.parse(file.toPath())
    require(!parsed.hasErrors()) { "Invalid release.toml" }
    val version = requireNotNull(parsed.getString("release.version"))
    require(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)").matches(version)) { "Invalid release version" }
    require(version.split('.').all { (it.toLongOrNull() ?: Long.MAX_VALUE) <= 9_007_199_254_740_991L }) { "Version number exceeds safe range" }
    val code = requireNotNull(parsed.getLong("release.version_code"))
    require(code in 1..2_100_000_000L) { "Invalid Android versionCode" }
    val summary = requireNotNull(parsed.getString("release.summary"))
    val changes = requireNotNull(parsed.getArray("release.changes"))
    require(changes.size() > 0) { "Release changes are required" }
    val lines = (0 until changes.size()).map {
        val value = changes.get(it)
        require(value is String) { "Release changes must be strings" }
        value
    }
    require((listOf(summary) + lines).all { it.isNotBlank() && it.none { c -> c == '\r' || c == '\n' || c == '\u0000' } }) { "Release descriptions must be nonempty single lines" }
    return mapOf("version" to version, "version_code" to code, "summary" to summary, "changes" to lines)
}

/**
 * 역할: aapt 결과가 정식 APK 메타데이터와 일치하는지 검사한다.
 * 입력: badging 출력과 기대 버전·코드.
 * 출력: 정상은 없음이며 비디버그·패키지·버전·SDK 불일치는 오류다.
 */
fun verifyReleaseBadging(text: String, version: String, code: Long) {
    val packages = Regex("^package: (.+)$", RegexOption.MULTILINE).findAll(text).toList()
    val sdk = Regex("^sdkVersion:'([0-9]+)'\\s*$", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
    require(packages.size == 1 && sdk == listOf("26") && !text.contains("application-debuggable")) { "Invalid release package, SDK or debuggable state" }
    val expected = mapOf("name" to "io.github.mchany.comciganneo", "versionCode" to code.toString(), "versionName" to version)
    for ((name, value) in expected) {
        val values = Regex("(?:^|\\s)${name}='([^']*)'").findAll(packages.single().groupValues[1]).map { it.groupValues[1] }.toList()
        require(values == listOf(value)) { "APK package or version does not match release.toml" }
    }
}

/**
 * 역할: ZIP의 개인 입력 파일과 내장 나이스 키를 검증한다.
 * 입력: APK와 로그에 노출하지 않을 출력 가능 ASCII 키.
 * 출력: 정상은 없음이며 누락·개인 파일·모호한 ZIP은 오류다.
 */
fun verifyReleaseContents(apk: File, key: String) {
    require(key.isNotEmpty() && key.all { it.code in 33..126 }) { "NEIS_API_KEY must be nonempty printable ASCII without whitespace" }
    val privateNames = setOf("neis.properties", "local.properties", "gradle.properties", "key.properties", "keystore.properties", "signing.properties", ".env")
    val privateExtensions = setOf("jks", "keystore", "pem", "key", "p12", "pfx")
    val names = mutableSetOf<String>()
    var found = false
    val limit = 64 * 1024 * 1024
    ZipFile(apk).use { zip ->
        for (entry in zip.entries().asSequence()) {
            val name = entry.name.replace('\\', '/')
            require(names.add(name) && !name.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(name) && ".." !in name.split('/')) { "APK has unsafe or duplicate ZIP entries" }
            val basename = name.substringAfterLast('/').lowercase()
            require(basename !in privateNames && basename.substringAfterLast('.', "") !in privateExtensions) { "APK contains a private build input" }
            if (Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex").matches(name)) {
                require(entry.size in 0..limit.toLong()) { "APK DEX exceeds verification limit" }
                val bytes = zip.getInputStream(entry).use { it.readNBytes(limit + 1) }
                require(bytes.size <= limit) { "APK DEX exceeds verification limit" }
                found = found || String(bytes, Charsets.ISO_8859_1).contains(key)
            }
        }
    }
    require(found) { "APK does not contain the configured NEIS key in DEX" }
}

/**
 * 역할: 합성 사례가 정상 경로로 통과하는 회귀를 발견한다.
 * 입력: 거부되어야 하는 검사 함수.
 * 출력: 실제로 거부하면 정상이며 예외가 없으면 테스트 실패다.
 */
fun expectReleaseFailure(action: () -> Unit) {
    var failed = false
    try { action() } catch (_: IllegalArgumentException) { failed = true }
    check(failed) { "Expected release validation to reject fixture" }
}

/**
 * 역할: 외부 엔티티와 DTD를 비활성화해 검사 보고서를 읽는다.
 * 입력: 로컬 JUnit 또는 Lint XML.
 * 출력: 안전하게 파싱한 문서 또는 오류다.
 */
fun releaseReport(file: File): org.w3c.dom.Document {
    require(file.isFile) { "Required verification report is missing" }
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
    factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
    return file.inputStream().use { factory.newDocumentBuilder().parse(it) }
}

/**
 * 역할: 실제 JUnit·Lint 보고서에서 이번 실행의 검증 근거를 수집한다.
 * 입력: 현재 프로젝트의 모듈별 빌드 보고서.
 * 출력: 성공 검사 목록이며 실패·누락 보고서는 오류다.
 */
fun releaseEvidence(): MutableList<String> {
    val reports = listOf(":core:model", ":core:comcigan", ":core:neis", ":app").flatMap { name ->
        rootProject.project(name).fileTree(rootProject.project(name).layout.buildDirectory.dir("test-results")) { include("*/TEST-*.xml") }.files
    }
    require(reports.isNotEmpty()) { "JUnit reports are required" }
    var count = 0L
    for (file in reports) {
        val suite = releaseReport(file).documentElement
        require(suite.tagName == "testsuite" && suite.getAttribute("failures").toLong() == 0L && suite.getAttribute("errors").toLong() == 0L) { "JUnit verification failed" }
        count += suite.getAttribute("tests").toLong() - (suite.getAttribute("skipped").toLongOrNull() ?: 0L)
    }
    require(count > 0) { "No executed JVM tests found" }
    for (variant in listOf("debug", "release")) {
        val document = releaseReport(layout.buildDirectory.file("reports/lint-results-${variant}.xml").get().asFile)
        require(document.documentElement.tagName == "issues") { "Invalid Lint report" }
        val issues = document.getElementsByTagName("issue")
        for (index in 0 until issues.length) {
            val severity = (issues.item(index) as org.w3c.dom.Element).getAttribute("severity").lowercase()
            require(severity !in setOf("error", "fatal")) { "Lint verification failed" }
        }
    }
    return mutableListOf("JUnit 보고서 기준 JVM 테스트 ${count}개 통과 (NO-SOURCE 태스크 제외)", "Debug·Release Lint 통과")
}

/**
 * 역할: aapt를 제한 시간 내에 실행하고 원문을 공개 로그에 출력하지 않는다.
 * 입력: SDK의 aapt와 APK 파일.
 * 출력: 정상 badging 문자열 또는 비밀값 없는 오류다.
 */
fun releaseBadging(tool: File, apk: File): String {
    require(tool.isFile && apk.isFile) { "Android SDK tool or APK missing" }
    val log = rootProject.layout.buildDirectory.file("release/aapt-badging.txt").get().asFile
    log.parentFile.mkdirs()
    val process = ProcessBuilder(tool.absolutePath, "dump", "badging", apk.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw IllegalArgumentException("APK metadata verification timed out")
    }
    require(process.exitValue() == 0 && log.length() <= 2 * 1024 * 1024) { "APK metadata verification failed" }
    return log.readText(Charsets.UTF_8)
}

tasks.register("exportReleaseMetadata") {
    group = "distribution"
    description = "Export validated TOML metadata for github-script."
    doLast {
        val destination = rootProject.layout.buildDirectory.file("release/release.json").get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(readReleaseMetadata(rootProject.file("release.toml")))) + "\n")
        logger.lifecycle("Validated release metadata exported")
    }
}

tasks.register("verifyReleaseApk") {
    group = "verification"
    description = "Verify production APK and actual reports before signing."
    dependsOn("assembleRelease")
    doLast {
        require(!emulatorSmoke && !releaseSmoke) { "Smoke APKs cannot be released" }
        val key = providers.environmentVariable("NEIS_API_KEY").orNull.orEmpty()
        require(key.isNotEmpty()) { "NEIS_API_KEY is required" }
        val sdk = providers.environmentVariable("ANDROID_HOME").orNull ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        require(!sdk.isNullOrBlank()) { "ANDROID_HOME is required" }
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "aapt.exe" else "aapt"
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk").get().asFile
        verifyReleaseBadging(releaseBadging(File(sdk, "build-tools/35.0.0/${executable}"), apk), releaseVersion, releaseCode)
        verifyReleaseContents(apk, key)
        val checks = releaseEvidence()
        checks.addAll(listOf("APK 앱 ID·버전·versionCode 및 Android 8.0 이상 설정 확인", "비디버그 정식 패키지 확인", "설정된 나이스 인증키의 DEX 반영 확인", "APK 내 개인 빌드 입력 파일 미포함 확인"))
        if (providers.gradleProperty("releaseUiCompileVerified").orNull == "true") checks.add("UI 계측 테스트 소스 컴파일 통과 (기기 실행 미수행)")
        val output = rootProject.file("unsigned").apply { mkdirs() }
        apk.copyTo(File(output, "app-release-unsigned.apk"), overwrite = true)
        File(output, "evidence.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("checks" to checks))) + "\n")
        logger.lifecycle("Production APK and verification reports checked")
    }
}
