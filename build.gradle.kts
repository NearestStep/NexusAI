plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "io.github.neareststep"
version = "1.2.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

sourceSets {
    create("loadtest") {
        java.setSrcDirs(listOf("src/loadtest/java"))
        resources.setSrcDirs(listOf("src/loadtest/resources"))
    }
    create("loadtestTest") {
        java.setSrcDirs(listOf("src/loadtest-test/java"))
        compileClasspath += sourceSets["loadtest"].output
        runtimeClasspath += sourceSets["loadtest"].output
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    // 1.20.6 is the oldest server this jar runs on. Newer Paper APIs are class file 69
    // (Java 25) and would not load on Java 21. Biome and other calls stay on the 1.20.6 shape.
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")

    testImplementation("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.12.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "loadtestCompileOnly"("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    "loadtestCompileOnly"("me.clip:placeholderapi:2.11.6")
    "loadtestCompileOnly"(sourceSets.main.get().output)
    "loadtestTestImplementation"(platform("org.junit:junit-bom:5.12.1"))
    "loadtestTestImplementation"("org.junit.jupiter:junit-jupiter")
    "loadtestTestImplementation"("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    "loadtestTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("NexusAI")

        relocate("com.fasterxml.jackson", "io.github.neareststep.nexusai.libs.jackson")
        relocate("com.github.benmanes.caffeine", "io.github.neareststep.nexusai.libs.caffeine")
    }

    build {
        dependsOn(shadowJar, "loadDriverJar", "eventsProbeJar")
    }

    test {
        useJUnitPlatform()
        dependsOn("loadtestTest")
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    compileTestJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    register<Jar>("loadDriverJar") {
        group = "build"
        description = "Paper load-test plugin. Not shaded into the release jar."
        archiveFileName.set("NexusAI-LoadDriver.jar")
        destinationDirectory.set(layout.buildDirectory.dir("loadtest"))
        from(sourceSets["loadtest"].output)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    register<Jar>("eventsProbeJar") {
        group = "build"
        description = "Logs NexusAI events for the smoke script. Not shaded into the release jar."
        archiveFileName.set("nexusai-events-probe.jar")
        destinationDirectory.set(layout.buildDirectory.dir("loadtest"))
        from(sourceSets["loadtest"].output) {
            exclude("plugin.yml")
        }
        from("src/loadtest/probe") {
            include("plugin.yml")
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    named<JavaCompile>("compileLoadtestJava") {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    named<JavaCompile>("compileLoadtestTestJava") {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    register<Test>("loadtestTest") {
        group = "verification"
        description = "Self-check for the Paper load driver."
        testClassesDirs = sourceSets["loadtestTest"].output.classesDirs
        classpath = sourceSets["loadtestTest"].runtimeClasspath
        useJUnitPlatform()
    }
}
