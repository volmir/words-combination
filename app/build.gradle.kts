import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val hasDefinitions = false
val dictionaryData = rootProject.layout.projectDirectory.dir("data")

val verifyDictionaryLicenses = tasks.register("verifyDictionaryLicenses") {
    description = "Проверяет происхождение словника и уровней."
    group = "verification"
    inputs.files(dictionaryData.file("manifest.json"), dictionaryData.file("words.txt"), dictionaryData.file("word_sources.jsonl.gz"), dictionaryData.file("levels.json"))
    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val manifest = JsonSlurper().parse(dictionaryData.file("manifest.json").asFile) as Map<*, *>
        val source = manifest["source"] as? Map<*, *>
        check(manifest["schema"] == "dictionary-provenance-v1" && source?.get("license") == "CC-BY-SA-4.0") {
            "Нет подтверждённого происхождения словника: пересоберите tools/build_dictionary.py."
        }
        check(sha256(dictionaryData.file("words.txt").asFile) == manifest["sha256_words_txt"] &&
            sha256(dictionaryData.file("word_sources.jsonl.gz").asFile) == manifest["sha256_word_sources"]) {
            "Словник или файл происхождения изменены после генерации."
        }
        check(sha256(dictionaryData.file("levels.json").asFile) == manifest["sha256_levels_json"] &&
            (manifest["levels_source"] as? Map<*, *>)?.get("license") == "MIT") {
            "Уровни изменены после генерации либо отсутствует их происхождение."
        }
    }
}

android {
    namespace = "com.example.sostavslova"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio.wordsgame.kymxpv"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "HAS_DEFINITIONS", hasDefinitions.toString())
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// В AGP 9 Kotlin встроен; общая версия KGP задана в корневом plugins-блоке.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
}

val syncDictionaryAssets = tasks.register<Sync>("syncDictionaryAssets") {
    description = "Копирует сгенерированные словари и опциональные определения в assets."
    group = "build"
    dependsOn(verifyDictionaryLicenses)
    from(dictionaryData) {
        include("words.txt", "levels.json", "word_sources.jsonl.gz")
    }
    from(rootProject.layout.projectDirectory.dir("legal")) {
        include("THIRD_PARTY_NOTICES.txt", "Apache-2.0.txt", "LIBRARY_NOTICES.txt", "RUNTIME_LIBRARIES.json")
    }
    into(layout.projectDirectory.dir("src/main/assets"))
    preserve {
        exclude("words.txt", "levels.json", "word_sources.jsonl.gz", "THIRD_PARTY_NOTICES.txt", "CC-BY-SA-4.0.txt", "Apache-2.0.txt", "LIBRARY_NOTICES.txt", "RUNTIME_LIBRARIES.json")
    }
}

abstract class CopyDictionaryTestAssets : DefaultTask() {
    @get:InputFile abstract val goldenFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun copy() {
        val directory = outputDirectory.get().asFile.apply { mkdirs() }
        goldenFile.get().asFile.copyTo(File(directory, "golden_search.json"), overwrite = true)
    }
}
val syncAndroidTestDictionary = tasks.register<CopyDictionaryTestAssets>("syncAndroidTestDictionary") {
    goldenFile.set(dictionaryData.file("golden_search.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/dictionary-test-assets"))
}

tasks.named("preBuild") {
    dependsOn(syncDictionaryAssets)
}

// androidx.core добавляет signature-permission для своих динамических receiver-ов.
// Приложение не регистрирует такие receiver-ы. Удаляем только этот маркер
// из объединённого манифеста, не запрашивая разрешений в исходном манифесте.
abstract class RemoveAndroidxReceiverPermission : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputManifest: RegularFileProperty

    @get:OutputFile
    abstract val outputManifest: RegularFileProperty

    @TaskAction
    fun removePermission() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(inputManifest.get().asFile)
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        for (tag in listOf("uses-permission", "permission")) {
            val nodes = document.getElementsByTagName(tag)
            for (i in nodes.length - 1 downTo 0) {
                val node = nodes.item(i)
                val attrValue = node.attributes?.getNamedItemNS(androidNamespace, "name")?.nodeValue
                if (attrValue != null && attrValue.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")) {
                    node.parentNode.removeChild(node)
                }
            }
        }
        check(listOf("uses-permission", "uses-permission-sdk-23").all {
            document.getElementsByTagName(it).length == 0
        }) { "Офлайн-приложение не должно запрашивать разрешений: проверьте зависимости." }
        val output = outputManifest.get().asFile
        output.parentFile.mkdirs()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(output))
    }
}

androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(
            syncAndroidTestDictionary, CopyDictionaryTestAssets::outputDirectory,
        )
        val removePermission = tasks.register<RemoveAndroidxReceiverPermission>(
            "${variant.name}RemoveAndroidxReceiverPermission",
        )
        variant.artifacts.use(removePermission)
            .wiredWithFiles(
                RemoveAndroidxReceiverPermission::inputManifest,
                RemoveAndroidxReceiverPermission::outputManifest,
            )
            .toTransform(SingleArtifact.MERGED_MANIFEST)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // Compose UI 1.12.1 транзитивно тянет Espresso 3.5.0, несовместимый с API 37.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
