plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // JsonElement API only (no compiler plugin needed), so :core stays plain Kotlin/JVM and testable without Android.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    test {
        // GoldenSuite is framework-free and shared with the app's on-device self-test and tools/kotlinc-local.sh
        kotlin.srcDir("src/testsupport/kotlin")
    }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    systemProperty("pb.golden", layout.projectDirectory.dir("src/test/resources/golden").asFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
