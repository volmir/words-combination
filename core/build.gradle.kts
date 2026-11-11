plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    // org.json нужен только тестам на JVM (на Android он есть в платформе)
    testImplementation(libs.json)
}

sourceSets {
    test {
        // words.txt, levels.json, golden_search.json берём прямо из ../data
        resources.srcDir("../data")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("auditDefinitionHints") {
    group = "verification"
    description = "Проверяет все локальные толкования обработчиком подсказок core (два пути через --args)."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.example.sostavslova.core.DefinitionHintDatasetAudit")
}

tasks.register<JavaExec>("auditCampaign") {
    group = "verification"
    description = "Независимая проверка кампании игровыми правилами core; --args='<input bundle> <output>'."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.example.sostavslova.core.CampaignDatasetAudit")
}
