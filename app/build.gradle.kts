import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val includeLocalReasoning = providers.gradleProperty("aivoraIncludeLocalReasoning").orNull == "true"

android {
    namespace = "com.chronoit.aivora"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.chronoit.aivora"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    androidResources { noCompress += "gguf" }
    if (includeLocalReasoning) sourceSets.getByName("main").assets.srcDir("src/localReasoning/assets")
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("libs/zxing-android-embedded-4.3.0.aar", "libs/zxing-core-3.4.1.jar"))
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-2.0.1.aar"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

val verifyReasoningModel by tasks.registering {
    val model = layout.projectDirectory.file("src/localReasoning/assets/models/reasoning/qwen3-0.6b-q4_0.gguf")
    inputs.file(model)
    doLast {
        check(model.asFile.length() == 428970080L) { "Pinned AIVORA reasoning model is missing or incomplete" }
        val digest = MessageDigest.getInstance("SHA-256")
        model.asFile.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4") { "Pinned AIVORA reasoning model hash mismatch" }
    }
}
if (includeLocalReasoning) tasks.named("preBuild") { dependsOn(verifyReasoningModel) }
