import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Properties
import javax.naming.ldap.LdapName
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val hasDefinitions = true
val dictionaryData = rootProject.layout.projectDirectory.dir("data")

// Только локальные секреты. Переменные окружения имеют приоритет для каждого поля.
val releaseProperties = Properties().apply {
    val localFile = rootProject.file("keystore.properties")
    if (localFile.isFile) localFile.inputStream().use { load(it) }
}
fun releaseSigningValue(property: String, environment: String): String? =
    providers.environmentVariable(environment).orNull ?: releaseProperties.getProperty(property)

val releaseStorePath = releaseSigningValue("storeFile", "SOSTAVSLOVA_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "SOSTAVSLOVA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "SOSTAVSLOVA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "SOSTAVSLOVA_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
val releaseKeystore = releaseStorePath?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Проверяет отдельный release-ключ без сборки APK и вывода секретов."
    doLast {
        if (releaseSigningValues.all { it == null }) {
            logger.warn("Release-ключ не настроен: артефакт будет НЕПОДПИСАННЫМ, НЕ предназначен для публикации или установки.")
        } else {
            check(releaseSigningConfigured) {
                "Неполная release-подпись: задайте все четыре поля в keystore.properties или SOSTAVSLOVA_RELEASE_*; значения скрыты."
            }
            val store = requireNotNull(releaseKeystore)
            check(store.isFile) { "Release-keystore недоступен; путь и секреты скрыты." }
            check(!store.name.equals("debug.keystore", ignoreCase = true) &&
                !releaseKeyAlias.equals("androiddebugkey", ignoreCase = true)) {
                "Debug-keystore или debug-alias запрещён для release."
            }
            val password = releaseStorePassword.orEmpty().toCharArray()
            val keystore = try {
                KeyStore.getInstance(store, password)
            } catch (_: Exception) {
                throw GradleException("Не удалось открыть release-keystore; проверьте файл и пароль. Значения скрыты.")
            } finally {
                password.fill('\u0000')
            }
            check(keystore.isKeyEntry(releaseKeyAlias)) { "Release-alias не содержит ключ; значение скрыто." }
            val certificate = keystore.getCertificate(releaseKeyAlias) as? X509Certificate
            check(certificate != null) { "Нет X.509-сертификата release-ключа." }
            check(LdapName(certificate.subjectX500Principal.name).rdns.none {
                it.type.equals("CN", ignoreCase = true) && it.value.toString().equals("Android Debug", ignoreCase = true)
            }) { "Отладочный сертификат Android Debug запрещён для release, включая переименованный ключ." }
        }
    }
}

tasks.matching {
    it.name in setOf("preReleaseBuild", "validateSigningRelease", "assembleRelease", "bundleRelease")
}.configureEach {
    dependsOn(verifyReleaseSigning)
}

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

// SHA установленной кампании попадает в BuildConfig, чтобы androidTest проверял
// реально упакованный asset, а не только исходный data/.
val installedManifest: Map<*, *>? = runCatching {
    JsonSlurper().parse(dictionaryData.file("manifest.json").asFile) as Map<*, *>
}.getOrNull()
val campaignSha256: String =
    ((installedManifest?.get("campaign_source") as? Map<*, *>)?.get("sha256") as? String).orEmpty()
val wordsSha256: String = (installedManifest?.get("sha256_words_txt") as? String).orEmpty()
val definitionsSha256: String =
    ((installedManifest?.get("definitions") as? Map<*, *>)?.get("sha256") as? String).orEmpty()

val verifyDefinitionsAudit = tasks.register<Exec>("verifyDefinitionsAudit") {
    description = "Требует решение владельца и отдельный полный ручной аудит подсказок; waiver недопустим."
    group = "verification"
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/build_game_data.py", "--check-release",
        "--out", dictionaryData.asFile.absolutePath)
}

tasks.matching { it.name in setOf("preReleaseBuild", "assembleRelease", "bundleRelease") }.configureEach {
    dependsOn(verifyDefinitionsAudit)
}

val verifyDictionaryLicenses = tasks.register("verifyDictionaryLicenses") {
    description = "Проверяет происхождение и хэши словаря, уровней, кампании и включённых определений."
    group = "verification"
    dependsOn("verifyOwnedHints", "verifyRuntimeNotices")
    inputs.files(dictionaryData.file("manifest.json"), dictionaryData.file("words.txt"), dictionaryData.file("word_sources.jsonl.gz"), dictionaryData.file("levels.json"))
    inputs.files(dictionaryData.file("campaign.json"))
    inputs.files(dictionaryData.file("definitions.db"))
    doLast {
        val manifest = JsonSlurper().parse(dictionaryData.file("manifest.json").asFile) as Map<*, *>
        val source = manifest["source"] as? Map<*, *>
        check(manifest["schema"] == "dictionary-provenance-v1" && source?.get("license") == "CC-BY-SA-4.0") {
            "Нет подтверждённого происхождения словаря: пересоберите tools/build_dictionary.py."
        }
        check(sha256(dictionaryData.file("words.txt").asFile) == manifest["sha256_words_txt"] &&
            sha256(dictionaryData.file("word_sources.jsonl.gz").asFile) == manifest["sha256_word_sources"]) {
            "Словарь или файл происхождения изменены после генерации."
        }
        check(sha256(dictionaryData.file("levels.json").asFile) == manifest["sha256_levels_json"] &&
            (manifest["levels_source"] as? Map<*, *>)?.get("license") == "MIT") {
            "Уровни изменены после генерации либо отсутствует их происхождение."
        }
        val campaignSource = manifest["campaign_source"] as? Map<*, *>
        val campaignFile = dictionaryData.file("campaign.json").asFile
        check(campaignFile.isFile && campaignSource?.get("profile") == "short15-release-v1" &&
            campaignSource?.get("sha256") == sha256(campaignFile) && campaignSource?.get("levels") == 15 &&
            campaignSource?.get("max_stars") == 49) {
            "Кампания отсутствует или не совпадает с manifest.campaign_source: установите проверенный short15-пакет."
        }
        val campaignLevels = (JsonSlurper().parse(campaignFile) as? Map<*, *>)?.get("levels") as? List<*>
        val campaignStars = campaignLevels?.sumOf { level ->
            ((level as? Map<*, *>)?.get("tasks") as? List<*>)?.size ?: 0
        } ?: 0
        check(campaignLevels?.size == 15 && campaignStars == 49) {
            "campaign.json должен содержать 15 уровней и 49 заданий/звёзд."
        }
        val database = dictionaryData.file("definitions.db").asFile
        if (hasDefinitions || database.exists()) {
            val definitions = manifest["definitions"] as? Map<*, *>
            check(database.isFile && definitions != null && definitions["sha256"] == sha256(database) &&
                definitions["dictionary_words_sha256"] == manifest["sha256_words_txt"]) {
                "База подсказок отсутствует, изменена или собрана для другого словаря. Пересоберите tools/build_owned_hints.py."
            }
        }
    }
}

tasks.register<Exec>("verifyOwnedHints") {
    description = "Проверяет воспроизводимость минимальных собственных подсказок и всех загадок T4."
    group = "verification"
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/build_owned_hints.py", "--check")
}

// Local audit input only: absolute cache paths never enter the APK or legal/.
tasks.register("exportRuntimeLicenseInputs") {
    group = "verification"
    description = "Экспортирует фактически разрешённые runtime-артефакты для проверки NOTICE."
    doLast {
        val profiles = listOf("debug", "release").associateWith { profile ->
            configurations.getByName("${profile}RuntimeClasspath").resolvedConfiguration.resolvedArtifacts
                .filter { it.moduleVersion.id.group != rootProject.group.toString() }
                .map { artifact ->
                    mapOf("artifact" to artifact.moduleVersion.id.toString(), "file" to artifact.file.absolutePath)
                }.filter { (it["file"] as String).contains("/caches/modules-2/") }
                .sortedBy { it["artifact"] }
        }
        val output = layout.buildDirectory.file("reports/licenses/runtime-inputs.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(profiles)) + "\n")
    }
}

tasks.register<Exec>("verifyRuntimeNotices") {
    group = "verification"
    dependsOn("exportRuntimeLicenseInputs")
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/build_library_notices.py", "--check")
}

android {
    namespace = "com.example.sostavslova"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.volmir.wordscombination"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "com.example.sostavslova.WordsTestRunner"
        buildConfigField("boolean", "HAS_DEFINITIONS", hasDefinitions.toString())
        buildConfigField("String", "CAMPAIGN_SHA256", "\"$campaignSha256\"")
        buildConfigField("String", "WORDS_SHA256", "\"$wordsSha256\"")
        buildConfigField("String", "DEFINITIONS_SHA256", "\"$definitionsSha256\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        // debug использует штатные keystore и signingConfig Android Gradle Plugin.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
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
    description = "Копирует сгенерированные словари, кампанию и опциональные определения в assets."
    group = "build"
    dependsOn(verifyDictionaryLicenses)
    from(dictionaryData) {
        include("words.txt", "levels.json", "campaign.json", "word_sources.jsonl.gz")
        if (hasDefinitions) include("definitions.db")
    }
    from(rootProject.layout.projectDirectory.dir("legal")) {
        include("THIRD_PARTY_NOTICES.txt", "Apache-2.0.txt", "CC-BY-SA-4.0.txt", "LIBRARY_NOTICES.txt", "RUNTIME_LIBRARIES.json")
    }
    from(rootProject.layout.projectDirectory) {
        include("LICENSE")
    }
    into(layout.projectDirectory.dir("src/main/assets"))
    preserve {
        exclude("words.txt", "levels.json", "campaign.json", "word_sources.jsonl.gz", "definitions.db", "THIRD_PARTY_NOTICES.txt", "LICENSE", "CC-BY-SA-4.0.txt", "Apache-2.0.txt", "LIBRARY_NOTICES.txt", "RUNTIME_LIBRARIES.json")
    }
    doLast {
        val manifest = JsonSlurper().parse(dictionaryData.file("manifest.json").asFile) as Map<*, *>
        val definitions = manifest["definitions"] as? Map<*, *>
        val assets = layout.projectDirectory.dir("src/main/assets").asFile
        val expectedAssets = setOf("words.txt", "levels.json", "campaign.json", "word_sources.jsonl.gz",
            "THIRD_PARTY_NOTICES.txt", "LICENSE", "CC-BY-SA-4.0.txt", "Apache-2.0.txt",
            "LIBRARY_NOTICES.txt", "RUNTIME_LIBRARIES.json") +
            if (hasDefinitions) setOf("definitions.db") else emptySet()
        val actualAssets = assets.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(assets).invariantSeparatorsPath }.toSet()
        check(actualAssets == expectedAssets) {
            "Непроверенные либо отсутствующие assets: ${(actualAssets - expectedAssets) + (expectedAssets - actualAssets)}"
        }
        check(sha256(File(assets, "campaign.json")) == (manifest["campaign_source"] as? Map<*, *>)?.get("sha256")) {
            "Скопированный campaign.json не совпадает с manifest.campaign_source."
        }
        check(sha256(File(assets, "words.txt")) == manifest["sha256_words_txt"] &&
            sha256(File(assets, "definitions.db")) == definitions?.get("sha256")) {
            "Скопированные словарь или определения не совпадают с manifest."
        }
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

val verifyPrivacyPublication = tasks.register<Exec>("verifyPrivacyPublication") {
    description = "Проверяет отсутствие персональных данных и сохранность уведомлений третьих лиц."
    group = "verification"
    dependsOn(syncDictionaryAssets)
    workingDir(rootProject.projectDir)
    commandLine(
        "python3", "tools/check_privacy_publication.py",
        "--root", rootProject.projectDir.absolutePath,
        "--require-generated",
    )
}

tasks.named("preBuild") {
    dependsOn(syncDictionaryAssets, verifyPrivacyPublication)
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
