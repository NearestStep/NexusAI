plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "io.github.neareststep"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
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
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    compileTestJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
