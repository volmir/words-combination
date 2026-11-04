plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
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
