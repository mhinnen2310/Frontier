import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    base
    id("com.diffplug.spotless") version "7.2.1" apply false
    id("com.gradleup.shadow") version "9.2.2" apply false
}

allprojects {
    group = "nl.frontier"
    version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.0.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat("1.28.0")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}

tasks.register("releaseJar") {
    group = "build"
    description = "Builds and verifies the deployable Frontier plugin JAR."
    dependsOn(":frontier-bootstrap:shadowJar", "check")
}
