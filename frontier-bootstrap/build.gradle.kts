plugins {
    `java-library`
    id("com.gradleup.shadow")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.60-beta")
    implementation(project(":frontier-api"))
    implementation(project(":frontier-city"))
    implementation(project(":frontier-influence"))
    implementation(project(":frontier-economy"))
    implementation(project(":frontier-warfare"))
    implementation(project(":frontier-repair"))
    implementation(project(":frontier-npc"))
    implementation(project(":frontier-world"))
    implementation(project(":frontier-ui-paper"))
    implementation(project(":frontier-persistence-postgres"))
    implementation(project(":frontier-observability"))
    implementation("io.micrometer:micrometer-core:1.15.2")
}

fun gitOutput(vararg arguments: String): String =
    runCatching {
        providers.exec {
            commandLine("git", *arguments)
        }.standardOutput.asText.get().trim()
    }.getOrDefault("unknown")

val frontierBuildValues = mapOf(
    "version" to project.version.toString(),
    "gitCommit" to gitOutput("rev-parse", "--short=12", "HEAD"),
    "buildTime" to gitOutput("show", "-s", "--format=%cI", "HEAD"),
)

tasks.processResources {
    inputs.properties(frontierBuildValues)
    filesMatching("frontier-build.properties") {
        expand(frontierBuildValues)
    }
}

tasks.shadowJar {
    archiveBaseName.set("TheFrontier")
    archiveClassifier.set("")
}

tasks.assemble { dependsOn(tasks.shadowJar) }
